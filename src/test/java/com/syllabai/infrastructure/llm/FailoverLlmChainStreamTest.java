package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Streamed chain failover (tutor SSE tranche): failover is free ONLY before
 * the first token — an error arriving before any delta moves the stream to
 * the next candidate; once committed, a mid-stream failure surfaces as an
 * error (never a second provider's text). Experiment pinning behaves
 * identically to the blocking path.
 */
class FailoverLlmChainStreamTest {

    /** A provider whose stream emits the scripted deltas and then fails. */
    private static LlmProvider streamThenFail(String name, List<String> deltas,
                                              LlmFailureClass failureClass) {
        return new LlmProvider() {
            @Override public String name() {
                return name;
            }

            @Override public boolean available() {
                return true;
            }

            @Override public LlmResponse generate(LlmRequest request) {
                throw new LlmProviderException(name, "generation failed (scripted)", null,
                        failureClass);
            }

            @Override public Flux<LlmDelta> stream(LlmRequest request) {
                return Flux.concat(
                        Flux.fromIterable(deltas).map(text -> new LlmDelta(text, name, "m-" + name)),
                        Flux.error(new LlmProviderException(name, "stream died mid-generation",
                                null, failureClass)));
            }

            @Override public LlmProviderHealth health() {
                return new LlmProviderHealth(true, true, 3, 60, 0, null);
            }
        };
    }

    @Test
    @DisplayName("failover before the first token: a dead provider's stream is replaced invisibly")
    void failoverBeforeFirstToken() {
        FakeLlmProvider dead = FakeLlmProvider.named("groq").alwaysFails(LlmFailureClass.RATE_LIMITED);
        FakeLlmProvider healthy = FakeLlmProvider.named("gemini").respondsWith("answer from gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(dead, healthy),
                ignored -> Optional.empty());

        List<LlmDelta> deltas = chain.stream(LlmRequest.of("s", "q")).collectList().block();

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).text()).isEqualTo("answer from gemini");
        assertThat(deltas.get(0).providerName()).isEqualTo("gemini");
        // the failed candidate paid one attempt; the healthy one served
        assertThat(dead.callCount()).isEqualTo(1);
        assertThat(healthy.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("committed after the first delta: a mid-stream death does NOT fail over")
    void committedAfterFirstDelta() {
        LlmProvider flaky = streamThenFail("groq", List.of("hello ", "world"),
                LlmFailureClass.PROVIDER_UNAVAILABLE);
        FakeLlmProvider spare = FakeLlmProvider.named("gemini").respondsWith("spare");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(flaky, spare),
                ignored -> Optional.empty());

        // materialize: [onNext(hello), onNext(world), onError]
        List<reactor.core.publisher.Signal<LlmDelta>> signals =
                chain.stream(LlmRequest.of("s", "q")).materialize().collectList().block();

        assertThat(signals).hasSize(3);
        assertThat(signals.get(0).get().text()).isEqualTo("hello ");
        assertThat(signals.get(1).get().text()).isEqualTo("world");
        assertThat(signals.get(2).isOnError()).isTrue();
        // the spare was NEVER called — resuming would duplicate or interleave text
        assertThat(spare.callCount()).isZero();
    }

    @Test
    @DisplayName("every candidate failing before the first token aggregates the failures")
    void allFailBeforeFirstToken() {
        FakeLlmProvider a = FakeLlmProvider.named("groq").alwaysFails(LlmFailureClass.RATE_LIMITED);
        FakeLlmProvider b = FakeLlmProvider.named("gemini").alwaysFails(LlmFailureClass.PROVIDER_UNAVAILABLE);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(a, b),
                ignored -> Optional.empty());

        assertThatThrownBy(() -> chain.stream(LlmRequest.of("s", "q")).collectList().block())
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed before first token")
                .hasMessageContaining("groq")
                .hasMessageContaining("gemini");
    }

    @Test
    @DisplayName("a pinned experiment streams from its provider alone — never fails over")
    void pinnedStreamNeverFailsOver() {
        FakeLlmProvider pinned = FakeLlmProvider.named("openrouter")
                .alwaysFails(LlmFailureClass.RATE_LIMITED);
        FakeLlmProvider healthy = FakeLlmProvider.named("groq").respondsWith("healthy");
        ExperimentPinResolver resolver = mock(ExperimentPinResolver.class);
        when(resolver.resolve(anyString()))
                .thenReturn(Optional.of(new ExperimentPin("exp-1", "openrouter", null)));
        FailoverLlmChain chain = new FailoverLlmChain(List.of(healthy, pinned), resolver);

        LlmRequest request = new LlmRequest("s", "q", null, null, null, "exp-1");

        assertThatThrownBy(() -> chain.stream(request).collectList().block())
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("before first token");
        // the healthy provider was never touched — pins have no failover (§26.1)
        assertThat(healthy.callCount()).isZero();
        assertThat(pinned.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a non-streaming provider degrades to a single-delta stream via generate()")
    void blockingProviderDegradesGracefully() {
        FakeLlmProvider blocking = FakeLlmProvider.named("groq")
                .respondsWith(new LlmResponse("whole answer", "groq", "llama-3.3-70b", 120, 10, 5));
        FailoverLlmChain chain = new FailoverLlmChain(List.of(blocking),
                ignored -> Optional.empty());

        List<LlmDelta> deltas = chain.stream(LlmRequest.of("s", "q")).collectList().block();

        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).text()).isEqualTo("whole answer");
        assertThat(deltas.get(0).providerName()).isEqualTo("groq");
        assertThat(deltas.get(0).model()).isEqualTo("llama-3.3-70b");
    }

    @Test
    @DisplayName("no available candidate errors before anything streams")
    void noCandidateErrors() {
        FailoverLlmChain chain = new FailoverLlmChain(
                List.of(FakeLlmProvider.unconfigured("groq")), ignored -> Optional.empty());

        assertThatThrownBy(() -> chain.stream(LlmRequest.of("s", "q")).collectList().block())
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider in chain");
    }
}
