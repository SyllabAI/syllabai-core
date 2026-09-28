package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Media-routing tests (HUB-ANSWER-BOX wave 3): a request carrying
 * {@link LlmMedia} must reach ONLY vision-capable members — a text-only provider
 * never receives an image it would merely fail on. Text-only requests are
 * unaffected (full chain order preserved).
 */
class FailoverLlmChainMediaTest {

    private static final LlmMedia IMAGE =
            new LlmMedia(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), "image/png");

    private static final ExperimentPinResolver UNPINNED = id -> Optional.empty();

    private static LlmRequest mediaRequest() {
        return LlmRequest.of("system", "user").withMedia(IMAGE);
    }

    @Test
    @DisplayName("media request skips text-only primary and routes to the vision-capable member")
    void mediaSkipsTextOnlyPrimary() {
        List<String> order = new java.util.ArrayList<>();
        FakeLlmProvider groq = FakeLlmProvider.named("groq").recordOrderInto(order);
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini").recordOrderInto(order);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini), UNPINNED);

        LlmResponse response = chain.generate(mediaRequest());

        assertThat(response.providerName()).isEqualTo("gemini");
        assertThat(groq.callCount()).as("text-only member must never see media").isZero();
        assertThat(order).containsExactly("gemini");
    }

    @Test
    @DisplayName("text-only request keeps the full chain order (groq first)")
    void textRequestUnaffected() {
        List<String> order = new java.util.ArrayList<>();
        FakeLlmProvider groq = FakeLlmProvider.named("groq").recordOrderInto(order);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq,
                FakeLlmProvider.visionCapable("gemini")), UNPINNED);

        LlmResponse response = chain.generate(LlmRequest.of("system", "user"));

        assertThat(response.providerName()).isEqualTo("groq");
        assertThat(order).containsExactly("groq");
    }

    @Test
    @DisplayName("media request with no vision-capable member fails with the explicit message")
    void noVisionCapableMember() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq"),
                FakeLlmProvider.named("openrouter")), UNPINNED);

        assertThatThrownBy(() -> chain.generate(mediaRequest()))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no vision-capable LLM provider available");
    }

    @Test
    @DisplayName("vision-capable member in cooldown is skipped; none left -> explicit failure")
    void visionMemberInCooldownSkipped() {
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini")
                .alwaysFails(LlmFailureClass.RATE_LIMITED);
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini), UNPINNED);

        // first three media requests: gemini fails (RATE_LIMITED) each time -> chain
        // exhausted; the third recorded failure crosses the health threshold, so
        // gemini is now cooling down
        for (int burn = 0; burn < 3; burn++) {
            assertThatThrownBy(() -> chain.generate(mediaRequest()))
                    .isInstanceOf(LlmProviderException.class)
                    .hasMessageContaining("RATE_LIMITED");
        }
        // fourth media request: gemini in cooldown -> no capable member left; the
        // text-only member is still NOT invoked
        assertThatThrownBy(() -> chain.generate(mediaRequest()))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no vision-capable");
        assertThat(groq.callCount()).isZero();
    }

    @Test
    @DisplayName("pinned media request to a text-only provider fails loudly (no silent routing)")
    void pinnedMediaToTextOnlyFails() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq"),
                FakeLlmProvider.visionCapable("gemini")),
                id -> Optional.of(new ExperimentPin("exp-1", "groq", null)));

        assertThatThrownBy(() -> chain.generate(
                new LlmRequest("system", "user", null, null, null, "exp-1").withMedia(IMAGE)))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("does not support media");
    }

    @Test
    @DisplayName("pinned media request to a vision-capable provider is served by it")
    void pinnedMediaToVisionProviderServed() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FakeLlmProvider gemini = FakeLlmProvider.visionCapable("gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin("exp-2", "gemini", null)));

        LlmResponse response = chain.generate(mediaRequest());

        assertThat(response.providerName()).isEqualTo("gemini");
        assertThat(groq.callCount()).isZero();
    }
}
