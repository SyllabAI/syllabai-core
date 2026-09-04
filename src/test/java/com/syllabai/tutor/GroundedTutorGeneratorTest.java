package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Grounded generation (T-024): the prompt is assembled from the question +
 * learner brief + numbered evidence; the chain's answer carries model
 * identity; an unavailable chain fails loudly instead of answering ungrounded.
 */
class GroundedTutorGeneratorTest {

    private RecordingProvider provider = new RecordingProvider(true);
    private final GroundedTutorGenerator generator =
            new GroundedTutorGenerator(provider, 0.2, 900);

    @Test
    @DisplayName("the user prompt embeds the question, briefs and numbered evidence")
    void promptAssembly() {
        ContextAssembler.TutorContext context = new ContextAssembler.TutorContext(
                "Learner state: no prior evidence on the topics in this question.",
                "Curriculum context:\n- topic IALCHEM2018-U1-T3: Bonding and Structure",
                List.of(
                        EvidenceItem.fromNode(UUID.randomUUID(), "IALCHEM2018-U1-T3", "TOPIC",
                                "Bonding and Structure", null, 0.5),
                        EvidenceItem.fromChunk(UUID.randomUUID(), "ms-doc", 2, UUID.randomUUID(),
                                4, "MARK_SCHEME",
                                "shapes of molecules determined by electron pair repulsion",
                                6, 6, List.of("e1"), "gemini", 0.81)));

        generator.generate("What shapes do molecules take?", context);

        String prompt = provider.lastRequest.userPrompt();
        assertThat(prompt).contains("QUESTION:\nWhat shapes do molecules take?");
        assertThat(prompt).contains("LEARNER CONTEXT:\nLearner state: no prior evidence");
        assertThat(prompt).contains("topic IALCHEM2018-U1-T3: Bonding and Structure");
        assertThat(prompt).contains("[1] (spec topic IALCHEM2018-U1-T3)");
        assertThat(prompt).contains("[2] (mark scheme, p6)");
        assertThat(prompt).contains("shapes of molecules determined by electron pair repulsion");

        assertThat(provider.lastRequest.systemPrompt()).contains("Answer ONLY from the numbered SOURCES");
        assertThat(provider.lastRequest.maxTokens()).isEqualTo(900);
    }

    @Test
    @DisplayName("oversized evidence is bounded in the prompt")
    void evidenceBounded() {
        String big = "x".repeat(5000);
        ContextAssembler.TutorContext context = new ContextAssembler.TutorContext(
                "brief", "kb", List.of(
                        EvidenceItem.fromChunk(UUID.randomUUID(), "d", 1, UUID.randomUUID(),
                                0, "OTHER", big, 1, 1, List.of(), "m", 0.9)));

        generator.generate("q", context);
        assertThat(provider.lastRequest.userPrompt().length()).isLessThan(2000);
    }

    @Test
    @DisplayName("answer carries model + provider identity (§19 traceability)")
    void answerIdentity() {
        TutorGenerator.GeneratedAnswer answer = generator.generate("q?",
                new ContextAssembler.TutorContext("b", "k", List.of()));
        assertThat(answer.answer()).isEqualTo("stub answer");
        assertThat(answer.model()).isEqualTo("llama-3.3-70b-versatile");
        assertThat(answer.provider()).isEqualTo("groq");
        assertThat(GroundedTutorGenerator.promptIdentity()).isEqualTo("tutor-grounded/v1");
    }

    @Test
    @DisplayName("unavailable chain fails loudly — never an ungrounded answer")
    void unavailableChainFails() {
        GroundedTutorGenerator offline =
                new GroundedTutorGenerator(new RecordingProvider(false), 0.2, 900);
        assertThatThrownBy(() -> offline.generate("q?",
                new ContextAssembler.TutorContext("b", "k", List.of())))
                .isInstanceOf(TutorGenerationException.class)
                .hasMessageContaining("LLM chain unavailable");
    }

    /** deterministic fake provider recording the request */
    private static final class RecordingProvider implements LlmProvider {
        private final boolean available;
        private LlmRequest lastRequest;

        RecordingProvider(boolean available) {
            this.available = available;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            this.lastRequest = request;
            return new LlmResponse("stub answer", "groq", "llama-3.3-70b-versatile",
                    120, 100, 40);
        }

        @Override
        public com.syllabai.infrastructure.llm.LlmProviderHealth health() {
            return new com.syllabai.infrastructure.llm.LlmProviderHealth(available);
        }
    }
}
