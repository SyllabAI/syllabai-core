package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.events.TutorAnsweredEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * V21 (P7): the learner-memory half of the Tutor pipeline. Only the
 * deterministic matcher's topic IDs, grounding strength and model identity are
 * recorded — one row per matched topic, nothing for anonymous previews, never
 * the raw chat text.
 */
class TutorEngagementRecorderTest {

    private final TutorTopicEngagementRepository engagements =
            mock(TutorTopicEngagementRepository.class);
    private final TutorEngagementRecorder recorder = new TutorEngagementRecorder(engagements);

    @Test
    @DisplayName("one engagement row per matched topic, carrying grounding provenance")
    void writesRowPerMatchedTopic() {
        UUID learner = UUID.randomUUID();
        UUID topicA = UUID.randomUUID();
        UUID topicB = UUID.randomUUID();
        Instant when = Instant.now();
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onTutorAnswered(new TutorAnsweredEvent(
                learner, "Why is NaCl ionic?", List.of(topicA, topicB),
                5, List.of("KNOWLEDGE_NODE", "DOCUMENT_CHUNK"), false,
                "openai/gpt-oss-120b", "tutor-grounded/v1", 2100.0, when, "EXPLANATION"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        List<TutorTopicEngagement> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.learnerId()).isEqualTo(learner);
            assertThat(row.occurredAt()).isEqualTo(when);
            assertThat(row.evidenceCount()).isEqualTo(5);
            assertThat(row.refused()).isFalse();
            assertThat(row.answerModel()).isEqualTo("openai/gpt-oss-120b");
        });
        assertThat(rows).extracting(TutorTopicEngagement::nodeId)
                .containsExactlyInAnyOrder(topicA, topicB);
    }

    @Test
    @DisplayName("deterministic refusal still records its (usually empty) match set honestly")
    void refusalCarriesRefusedFlag() {
        UUID learner = UUID.randomUUID();
        UUID weakMatch = UUID.randomUUID();
        recorder.onTutorAnswered(new TutorAnsweredEvent(
                learner, "odd question", List.of(weakMatch),
                0, List.of(), true, null, "tutor-grounded/v1", 4.0, Instant.now(), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        TutorTopicEngagement row = captor.getValue().get(0);
        assertThat(row.refused()).isTrue();
        assertThat(row.evidenceCount()).isZero();
        assertThat(row.answerModel()).isNull();
    }

    @Test
    @DisplayName("anonymous previews (null learner) write nothing to learner memory")
    void anonymousPreviewWritesNothing() {
        recorder.onTutorAnswered(new TutorAnsweredEvent(
                null, "preview question", List.of(UUID.randomUUID()),
                3, List.of("KNOWLEDGE_NODE"), false, "m", "tutor-grounded/v1", 10.0, Instant.now(), null));
        verify(engagements, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("asks with no deterministic topic match write nothing")
    void noMatchNoRows() {
        recorder.onTutorAnswered(new TutorAnsweredEvent(
                UUID.randomUUID(), "hello?", List.of(),
                0, List.of(), true, null, "tutor-grounded/v1", 2.0, Instant.now(), null));
        verify(engagements, never()).saveAll(anyList());
    }

    // -- V23: deterministic signal classification --------------------------------

    @Test
    @DisplayName("signal classifier: precedence misconception > doubt > explanation > default")
    void signalClassificationPrecedence() {
        assertThat(TutorEngagementRecorder.classify(
                "explain bonding", "MISCONCEPTION_REMEDIATION"))
                .isEqualTo("MISCONCEPTION_RELATED");
        assertThat(TutorEngagementRecorder.classify(
                "I am confused about moles", "EXPLANATION"))
                .isEqualTo("DOUBT_SIGNAL");
        assertThat(TutorEngagementRecorder.classify(
                "explain dynamic equilibrium", "EXPLANATION"))
                .isEqualTo("EXPLANATION_REQUEST");
        assertThat(TutorEngagementRecorder.classify("moles and titration", null))
                .isEqualTo("TOPIC_ENGAGEMENT");
        assertThat(TutorEngagementRecorder.classify(null, null))
                .isEqualTo("TOPIC_ENGAGEMENT");
    }

    // -- V25 (sprint-2 §9): classifier v2 — PREREQUISITE_HELP + CLARIFICATION_REQUEST

    @Test
    @DisplayName("§9: a PREREQUISITE_REVIEW intervention plan classifies PREREQUISITE_HELP")
    void prerequisiteReviewPlanClassifiesPrerequisiteHelp() {
        assertThat(TutorEngagementRecorder.classify(
                "how do I balance this equation?", "PREREQUISITE_REVIEW"))
                .isEqualTo("PREREQUISITE_HELP");
    }

    @Test
    @DisplayName("§9: clarification phrases classify CLARIFICATION_REQUEST, before explanation patterns")
    void clarificationPhrasesClassifyBeforeExplanation() {
        assertThat(TutorEngagementRecorder.classify(
                "can you clarify what a mole is?", null))
                .isEqualTo("CLARIFICATION_REQUEST");
        // "what do you mean" would match the "what do" explanation pattern —
        // the clarification list is checked FIRST, so it stays a clarification
        assertThat(TutorEngagementRecorder.classify(
                "what do you mean by empirical formula?", null))
                .isEqualTo("CLARIFICATION_REQUEST");
        assertThat(TutorEngagementRecorder.classify(
                "sorry, say that again please", null))
                .isEqualTo("CLARIFICATION_REQUEST");
    }

    @Test
    @DisplayName("§9: a doubt phrase still outranks a clarification phrase")
    void doubtOutranksClarification() {
        assertThat(TutorEngagementRecorder.classify(
                "I'm confused — can you clarify?", null))
                .isEqualTo("DOUBT_SIGNAL");
    }

    @Test
    @DisplayName("§9: new rows carry the classifier policy version; legacy default is v1")
    void rowsCarryClassifierVersion() {
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onTutorAnswered(new TutorAnsweredEvent(
                UUID.randomUUID(), "explain moles", List.of(UUID.randomUUID()),
                3, List.of("KNOWLEDGE_NODE"), false, "m", "tutor-grounded/v1",
                100.0, Instant.now(), "EXPLANATION"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).classifierVersion())
                .isEqualTo("tutor-signals/v2");
        // the legacy constructors (pre-V25 callers, fixtures) default to v1
        assertThat(new TutorTopicEngagement(UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), 1, false, "m").classifierVersion())
                .isEqualTo("tutor-signals/v1");
    }

    @Test
    @DisplayName("engagement rows carry the classified signal type (never the raw text)")
    void rowsCarrySignalType() {
        UUID learner = UUID.randomUUID();
        UUID topic = UUID.randomUUID();
        Instant when = Instant.now();
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onTutorAnswered(new TutorAnsweredEvent(
                learner, "I don't understand ionic bonding", List.of(topic),
                4, List.of("KNOWLEDGE_NODE"), false,
                "openai/gpt-oss-120b", "tutor-grounded/v1", 1800.0, when, "EXPLANATION"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).signalType()).isEqualTo("DOUBT_SIGNAL");
    }

    // -- V24: the Contextual Learning Assistant attaches via the extension rules --

    @Test
    @DisplayName("CLA exchange appends rows with surface=CONTEXTUAL_ASSISTANT + the context identity triple")
    void claInteractionWritesProvenanceRows() {
        UUID learner = UUID.randomUUID();
        UUID topic = UUID.randomUUID();
        Instant when = Instant.now();
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onClaInteraction(new com.syllabai.shared.events.ClaInteractionEvent(
                learner, "explain ionic bonding", List.of(topic),
                5, List.of("KNOWLEDGE_NODE", "MARK_SCHEME"), false,
                "stub-model", "tutor-grounded/v2", 1500.0, when, "EXPLANATION",
                "EXPLAIN", "KG_TOPIC", topic, List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        List<TutorTopicEngagement> rows = captor.getValue();
        assertThat(rows).hasSize(1);
        TutorTopicEngagement row = rows.get(0);
        assertThat(row.learnerId()).isEqualTo(learner);
        assertThat(row.nodeId()).isEqualTo(topic);
        assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
        assertThat(row.responseMode()).isEqualTo("EXPLAIN");
        assertThat(row.contextKind()).isEqualTo("KG_TOPIC");
        assertThat(row.contextReference()).isEqualTo(topic);
        // same deterministic classification precedence as tutor rows
        assertThat(row.signalType()).isEqualTo("EXPLANATION_REQUEST");
        assertThat(row.evidenceCount()).isEqualTo(5);
        assertThat(row.refused()).isFalse();
        assertThat(row.answerModel()).isEqualTo("stub-model");
    }

    @Test
    @DisplayName("CLA misconception-remediation policy signal classifies MISCONCEPTION_RELATED on the CLA surface")
    void claMisconceptionSignal() {
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onClaInteraction(new com.syllabai.shared.events.ClaInteractionEvent(
                UUID.randomUUID(), "why do I keep getting this wrong?",
                List.of(UUID.randomUUID()), 3, List.of("KNOWLEDGE_NODE"), false,
                "m", "tutor-grounded/v2", 900.0, Instant.now(), "MISCONCEPTION_REMEDIATION",
                "EXPLAIN", "KG_TOPIC", UUID.randomUUID(), List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).signalType()).isEqualTo("MISCONCEPTION_RELATED");
        assertThat(captor.getValue().get(0).surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
    }

    @Test
    @DisplayName("CLA refused asks keep their anchored topic rows (the unresolved signal)")
    void claRefusedAskKeepsRows() {
        UUID learner = UUID.randomUUID();
        UUID topic = UUID.randomUUID();
        when(engagements.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        recorder.onClaInteraction(new com.syllabai.shared.events.ClaInteractionEvent(
                learner, "explain something with no sources", List.of(topic),
                0, List.of(), true, null, "tutor-grounded/v2", 20.0, Instant.now(), null,
                "SUMMARIZE", "KG_TOPIC", topic, List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TutorTopicEngagement>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(engagements).saveAll(captor.capture());
        TutorTopicEngagement row = captor.getValue().get(0);
        assertThat(row.refused()).isTrue();
        assertThat(row.evidenceCount()).isZero();
        assertThat(row.answerModel()).isNull();
        assertThat(row.responseMode()).isEqualTo("SUMMARIZE");
    }

    @Test
    @DisplayName("CLA anonymous/defensive null learner writes nothing")
    void claNullLearnerWritesNothing() {
        recorder.onClaInteraction(new com.syllabai.shared.events.ClaInteractionEvent(
                null, "preview", List.of(UUID.randomUUID()),
                2, List.of("KNOWLEDGE_NODE"), false, "m", "tutor-grounded/v2", 5.0,
                Instant.now(), "EXPLANATION", "EXPLAIN", "KG_TOPIC", UUID.randomUUID(),
                List.of()));
        verify(engagements, never()).saveAll(anyList());
    }
}
