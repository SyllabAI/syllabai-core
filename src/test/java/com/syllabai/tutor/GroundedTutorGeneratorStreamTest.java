package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.infrastructure.llm.FakeLlmProvider;
import com.syllabai.infrastructure.llm.LlmDelta;
import com.syllabai.infrastructure.llm.LlmFailureClass;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmProviderHealth;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Streamed grounded generation (tutor SSE tranche): the SAME prompt assembly
 * and the SAME output hygiene as the blocking path — the concatenation of
 * emitted deltas equals {@code sanitizeAnswer(fullRawText)} — plus the
 * fixed client-safe failure text (deep-audit M2) on chain failures.
 */
class GroundedTutorGeneratorStreamTest {

    private static final Pattern FENCE_OPEN = Pattern.compile("<<<UNTRUSTED-([A-Z2-9]{8})>>>");
    private static final Pattern FENCE_CLOSE = Pattern.compile("<<<END-UNTRUSTED-([A-Z2-9]{8})>>>");

    private static ContextAssembler.TutorContext contextWithEvidence(int count) {
        List<EvidenceItem> evidence = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            evidence.add(EvidenceItem.fromNode(UUID.randomUUID(), "IALCHEM2018-U1-T" + (i + 1),
                    "TOPIC", "Topic " + i, null, 0.5));
        }
        return new ContextAssembler.TutorContext("learner brief", "curriculum brief",
                evidence);
    }

    @Test
    @DisplayName("an unavailable chain errors with the fixed client-safe message")
    void unavailableChainErrors() {
        GroundedTutorGenerator generator = new GroundedTutorGenerator(
                FakeLlmProvider.unconfigured("chain"), 0.2, 900);

        assertThat(
                generator.streamGenerate("q", List.of(), contextWithEvidence(1))
                        .materialize().collectList().block())
                .anySatisfy(signal -> {
                    assertThat(signal.isOnError()).isTrue();
                    assertThat(signal.getThrowable())
                            .isInstanceOf(TutorGenerationException.class)
                            .hasMessage(GroundedTutorGenerator.UNAVAILABLE_MESSAGE);
                });
    }

    @Test
    @DisplayName("streamed output has byte parity with the blocking-path sanitizer for ANY split")
    void streamParityWithBlockingSanitizer() {
        // a provider that streams its answer in arbitrary 7-char chunks, and
        // poisons the raw text with a fence echo + an out-of-range marker,
        // using the REAL per-request nonce it reads from the prompt
        AtomicReference<String> rawStreamed = new AtomicReference<>();
        LlmProvider chunkingPoisoner = new LlmProvider() {
            @Override public String name() {
                return "stub";
            }

            @Override public boolean available() {
                return true;
            }

            @Override public LlmResponse generate(LlmRequest request) {
                throw new IllegalStateException("not used in this test");
            }

            @Override public Flux<LlmDelta> stream(LlmRequest request) {
                String open = extract(FENCE_OPEN, request.userPrompt());
                String close = extract(FENCE_CLOSE, request.userPrompt());
                String raw = "Moles count particles [1] cleanly. " + open + "echo" + close
                        + " The fake claim [9] is stripped too.";
                rawStreamed.set(raw);
                List<String> chunks = new ArrayList<>();
                for (int i = 0; i < raw.length(); i += 7) {
                    chunks.add(raw.substring(i, Math.min(raw.length(), i + 7)));
                }
                return Flux.fromIterable(chunks)
                        .map(chunk -> new LlmDelta(chunk, "stub", "stub-model"));
            }

            @Override public LlmProviderHealth health() {
                return new LlmProviderHealth(true, true, 3, 60, 0, null);
            }
        };
        GroundedTutorGenerator generator = new GroundedTutorGenerator(chunkingPoisoner, 0.2, 900);

        List<TutorGenerator.GeneratedDelta> deltas =
                generator.streamGenerate("q", List.of(), contextWithEvidence(2))
                        .collectList().block();

        // parity: joined emitted deltas == the blocking sanitizer on the raw text
        String raw = rawStreamed.get();
        String open = extract(FENCE_OPEN, raw);
        String close = extract(FENCE_CLOSE, raw);
        String expected = GroundedTutorGenerator.sanitizeAnswer(raw, 2, open, close);
        String joined = String.join("", deltas.stream()
                .map(TutorGenerator.GeneratedDelta::answer).toList());
        assertThat(joined).isEqualTo(expected);
        assertThat(joined).doesNotContain("[9]").doesNotContain("<<<UNTRUSTED");
        assertThat(joined).contains("[1]");

        // identity rides the FIRST emitted delta
        assertThat(deltas.get(0).provider()).isEqualTo("stub");
        assertThat(deltas.get(0).model()).isEqualTo("stub-model");
    }

    @Test
    @DisplayName("a chain stream failure surfaces as the fixed client-safe generation error")
    void chainFailureSurfacesClientSafe() {
        FakeLlmProvider dead = FakeLlmProvider.named("groq").alwaysFails(LlmFailureClass.RATE_LIMITED);
        GroundedTutorGenerator generator = new GroundedTutorGenerator(dead, 0.2, 900);

        List<reactor.core.publisher.Signal<TutorGenerator.GeneratedDelta>> signals =
                generator.streamGenerate("q", List.of(), contextWithEvidence(1))
                        .materialize().collectList().block();

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).isOnError()).isTrue();
        assertThat(signals.get(0).getThrowable())
                .isInstanceOf(TutorGenerationException.class)
                .hasMessage(GroundedTutorGenerator.UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("empty provider deltas are filtered, not delivered as gaps")
    void emptyDeltasFiltered() {
        LlmProvider gappy = new LlmProvider() {
            @Override public String name() {
                return "stub";
            }

            @Override public boolean available() {
                return true;
            }

            @Override public LlmResponse generate(LlmRequest request) {
                throw new IllegalStateException("not used");
            }

            @Override public Flux<LlmDelta> stream(LlmRequest request) {
                return Flux.just(
                        new LlmDelta("", "stub", "m"),
                        new LlmDelta("real text", "stub", "m"),
                        new LlmDelta("", "stub", "m"));
            }

            @Override public LlmProviderHealth health() {
                return new LlmProviderHealth(true, true, 3, 60, 0, null);
            }
        };
        GroundedTutorGenerator generator = new GroundedTutorGenerator(gappy, 0.2, 900);

        List<TutorGenerator.GeneratedDelta> deltas =
                generator.streamGenerate("q", List.of(), contextWithEvidence(1))
                        .collectList().block();

        String joined = String.join("", deltas.stream()
                .map(TutorGenerator.GeneratedDelta::answer).toList());
        assertThat(joined).isEqualTo("real text");
    }

    private static String extract(Pattern pattern, String prompt) {
        Matcher m = pattern.matcher(prompt);
        if (!m.find()) {
            throw new AssertionError("no fence marker in prompt\n" + prompt);
        }
        return m.group();
    }
}
