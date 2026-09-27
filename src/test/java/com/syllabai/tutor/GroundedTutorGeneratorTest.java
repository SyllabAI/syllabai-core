package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.infrastructure.llm.FakeLlmProvider;
import com.syllabai.infrastructure.llm.LlmResponse;
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

    private final FakeLlmProvider provider = FakeLlmProvider.named("recording")
            .respondsWith(new LlmResponse("stub answer", "groq", "llama-3.3-70b-versatile", 120, 100, 40));
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

        String prompt = provider.lastRequest().userPrompt();
        assertThat(prompt).contains("QUESTION:\nWhat shapes do molecules take?");
        assertThat(prompt).contains("LEARNER CONTEXT:\nLearner state: no prior evidence");
        assertThat(prompt).contains("topic IALCHEM2018-U1-T3: Bonding and Structure");
        assertThat(prompt).contains("[1] (spec topic IALCHEM2018-U1-T3)");
        assertThat(prompt).contains("[2] (mark scheme, p6)");
        assertThat(prompt).contains("shapes of molecules determined by electron pair repulsion");

        assertThat(provider.lastRequest().systemPrompt()).contains("Answer ONLY from the numbered SOURCES");
        assertThat(provider.lastRequest().maxTokens()).isEqualTo(900);
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
        assertThat(provider.lastRequest().userPrompt().length()).isLessThan(2000);
    }

    @Test
    @DisplayName("answer carries model + provider identity (§19 traceability)")
    void answerIdentity() {
        TutorGenerator.GeneratedAnswer answer = generator.generate("q?",
                new ContextAssembler.TutorContext("b", "k", List.of()));
        assertThat(answer.answer()).isEqualTo("stub answer");
        assertThat(answer.model()).isEqualTo("llama-3.3-70b-versatile");
        assertThat(answer.provider()).isEqualTo("groq");
        assertThat(GroundedTutorGenerator.promptIdentity()).isEqualTo("tutor-grounded/v4");
    }

    @Test
    @DisplayName("v3 pins the markdown + mhchem formatting rules (s138 rendering contract)")
    void formattingRulesPinned() {
        String system = generator.systemPrompt();
        assertThat(system).contains("GitHub-flavored markdown");
        assertThat(system).contains("mhchem");
        assertThat(system).contains("$\\ce{H2O}$");
        assertThat(system).contains("Never use HTML tags or Unicode sub/superscripts");
        // the v2 grounding rules survive verbatim inside v3
        assertThat(system).contains("Answer ONLY from the numbered SOURCES");
        assertThat(system).contains("at most 200 words plus citations");
    }

    @Test
    @DisplayName("v4 pins the working-memory rule: earlier turns resolve references, never serve as sources")
    void workingMemoryRulePinned() {
        String system = generator.systemPrompt();
        assertThat(system).contains("CONVERSATION SO FAR block, when present");
        assertThat(system).contains("cite ONLY the SOURCES numbered in this message");
        assertThat(system).contains("do not repeat an earlier answer verbatim");
        // the v3 formatting rules survive verbatim inside v4
        assertThat(system).contains("GitHub-flavored markdown");
    }

    @Test
    @DisplayName("history renders as a CONVERSATION SO FAR block, oldest first, before the QUESTION")
    void conversationBlockRendered() {
        ContextAssembler.TutorContext context = new ContextAssembler.TutorContext("b", "k",
                List.of(EvidenceItem.fromNode(UUID.randomUUID(), "C", "TOPIC", "T", null, 0.5)));
        List<ConversationTurn> history = List.of(
                new ConversationTurn(ConversationTurn.ROLE_USER, "How do I calculate moles?"),
                new ConversationTurn(ConversationTurn.ROLE_ASSISTANT,
                        "Divide mass by Mr: moles = mass/Mr."),
                new ConversationTurn(ConversationTurn.ROLE_USER, "why is that?"));

        generator.generate("why is that?", history, context);

        String prompt = provider.lastRequest().userPrompt();
        int conversationAt = prompt.indexOf("CONVERSATION SO FAR");
        int questionAt = prompt.indexOf("QUESTION:\nwhy is that?");
        assertThat(conversationAt).isGreaterThanOrEqualTo(0);
        assertThat(questionAt).isGreaterThan(conversationAt);
        assertThat(prompt).contains("LEARNER: How do I calculate moles?");
        assertThat(prompt).contains("TUTOR: Divide mass by Mr: moles = mass/Mr.");
        assertThat(prompt).contains("LEARNER: why is that?");
        // oldest-first: the first learner turn precedes the tutor turn
        assertThat(prompt.indexOf("LEARNER: How do I calculate moles?"))
                .isLessThan(prompt.indexOf("TUTOR: Divide mass by Mr"));
    }

    @Test
    @DisplayName("no history ⇒ no conversation block (anchored single-turn callers keep the v3 prompt shape)")
    void noConversationBlockWhenNoHistory() {
        generator.generate("single turn?",
                new ContextAssembler.TutorContext("b", "k", List.of()));
        assertThat(provider.lastRequest().userPrompt()).doesNotContain("CONVERSATION SO FAR");
        assertThat(provider.lastRequest().userPrompt()).contains("QUESTION:\nsingle turn?");

        generator.generate("empty list?", List.<ConversationTurn>of(),
                new ContextAssembler.TutorContext("b", "k", List.of()));
        assertThat(provider.lastRequest().userPrompt()).doesNotContain("CONVERSATION SO FAR");
    }

    @Test
    @DisplayName("the conversation budget keeps the newest turns whole and bounds the prompt")
    void conversationBudgetBounded() {
        ContextAssembler.TutorContext context = new ContextAssembler.TutorContext("b", "k", List.of());
        List<ConversationTurn> history = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new ConversationTurn(ConversationTurn.ROLE_USER,
                    "turn " + i + " " + "x".repeat(700)));
        }

        generator.generate("follow-up", history, context);

        String prompt = provider.lastRequest().userPrompt();
        // the oldest turns are dropped once the budget is spent…
        assertThat(prompt).doesNotContain("turn 0 ");
        // …while the newest turns survive whole
        assertThat(prompt).contains("turn 9 ");
        // and the block stays inside its budget (plus labels/newlines)
        int from = prompt.indexOf("CONVERSATION SO FAR");
        int to = prompt.indexOf("QUESTION:");
        assertThat(to - from).isLessThanOrEqualTo(2400 + 400);
    }

    @Test
    @DisplayName("unavailable chain fails loudly — never an ungrounded answer")
    void unavailableChainFails() {
        GroundedTutorGenerator offline =
                new GroundedTutorGenerator(FakeLlmProvider.unconfigured("recording"), 0.2, 900);
        assertThatThrownBy(() -> offline.generate("q?",
                new ContextAssembler.TutorContext("b", "k", List.of())))
                .isInstanceOf(TutorGenerationException.class)
                .hasMessageContaining("LLM chain unavailable");
    }
}
