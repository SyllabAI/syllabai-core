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
                "openai/gpt-oss-120b", "tutor-grounded/v1", 2100.0, when));

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
                0, List.of(), true, null, "tutor-grounded/v1", 4.0, Instant.now()));

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
                3, List.of("KNOWLEDGE_NODE"), false, "m", "tutor-grounded/v1", 10.0, Instant.now()));
        verify(engagements, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("asks with no deterministic topic match write nothing")
    void noMatchNoRows() {
        recorder.onTutorAnswered(new TutorAnsweredEvent(
                UUID.randomUUID(), "hello?", List.of(),
                0, List.of(), true, null, "tutor-grounded/v1", 2.0, Instant.now()));
        verify(engagements, never()).saveAll(anyList());
    }
}
