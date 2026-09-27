package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.TutorTopicEngagement;
import com.syllabai.learner.TutorTopicEngagementRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-session tutor memory (s140): the digest is a deterministic,
 * matched-topic-scoped summary of what the learner has already done —
 * earlier asks (engagement rows), practice outcomes (BKT skill state) and
 * pending reviews — with counts and recency only, never probabilities. No
 * history ⇒ no digest (the block is omitted, fresh-learner prompt shape
 * preserved).
 */
class TutorMemoryServiceTest {

    private final TutorTopicEngagementRepository engagements =
            mock(TutorTopicEngagementRepository.class);
    private final ReviewScheduleRepository reviews = mock(ReviewScheduleRepository.class);
    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final TutorMemoryService service =
            new TutorMemoryService(engagements, reviews, learnerModel);

    private final UUID learner = UUID.randomUUID();
    private final UUID moles = UUID.randomUUID();
    private final UUID bonding = UUID.randomUUID();

    private TutorTopicEngagement ask(UUID node, String signal, Instant at) {
        return new TutorTopicEngagement(learner, node, at, 3, false, "m", signal);
    }

    @Test
    @DisplayName("the digest weaves asks, practice outcomes and review status per topic")
    void fullDigest() {
        Instant threeDaysAgo = Instant.now().minusSeconds(3 * 24 * 3600);
        Instant twoDaysAgo = Instant.now().minusSeconds(2 * 24 * 3600);
        when(engagements.findByLearnerIdAndNodeIdIn(learner, List.of(moles, bonding)))
                .thenReturn(List.of(
                        ask(moles, "DOUBT_SIGNAL", threeDaysAgo),
                        ask(moles, "DOUBT_SIGNAL", threeDaysAgo.plusSeconds(60)),
                        ask(moles, "EXPLANATION_REQUEST", threeDaysAgo.plusSeconds(120)),
                        ask(bonding, "TOPIC_ENGAGEMENT", twoDaysAgo)));
        when(reviews.findByLearnerIdAndNodeIdInAndStatus(learner, List.of(moles, bonding),
                ReviewSchedule.Status.PENDING))
                .thenReturn(List.of(new ReviewSchedule(learner, moles, Instant.now(),
                        ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, 0.4)));
        SkillState molesState = new SkillState(learner, moles, 0.1, twoDaysAgo);
        molesState.recordAttempt(true, 0.3, twoDaysAgo);
        molesState.recordAttempt(true, 0.5, twoDaysAgo);
        molesState.recordAttempt(false, 0.4, twoDaysAgo);
        when(learnerModel.skillStates(learner)).thenReturn(List.of(molesState));

        String digest = service.digest(learner, List.of(
                new TutorMemoryService.TopicRef(moles, "Moles"),
                new TutorMemoryService.TopicRef(bonding, "Bonding")));

        assertThat(digest).isNotNull();
        String molesLine = digest.lines().filter(l -> l.contains("'Moles'")).findFirst().orElse("");
        assertThat(molesLine).contains("3 earlier tutor ask(s)");
        assertThat(molesLine).contains("mostly doubt-checks"); // 2 of 3 doubt signals
        assertThat(molesLine).contains("practiced 3 time(s), 2 correct");
        assertThat(molesLine).contains("due for a spaced review");
        // counts and recency only — never mastery numbers
        assertThat(molesLine).doesNotContain("0.4");
        assertThat(digest).contains("'Bonding'");
        assertThat(digest).contains("1 earlier tutor ask");
        assertThat(digest).doesNotContain("mostly doubt-checks'Bonding'");
    }

    @Test
    @DisplayName("no history on any matched topic ⇒ null digest (block omitted entirely)")
    void noHistoryNoDigest() {
        when(engagements.findByLearnerIdAndNodeIdIn(learner, List.of(moles))).thenReturn(List.of());
        when(reviews.findByLearnerIdAndNodeIdInAndStatus(learner, List.of(moles),
                ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(learnerModel.skillStates(learner)).thenReturn(List.of());

        assertThat(service.digest(learner, List.of(new TutorMemoryService.TopicRef(moles, "Moles"))))
                .isNull();
    }

    @Test
    @DisplayName("practice-only history still digests (a learner who never asked but has BKT state)")
    void practiceOnlyDigest() {
        when(engagements.findByLearnerIdAndNodeIdIn(learner, List.of(moles))).thenReturn(List.of());
        when(reviews.findByLearnerIdAndNodeIdInAndStatus(learner, List.of(moles),
                ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        SkillState state = new SkillState(learner, moles, 0.1, Instant.now().minusSeconds(3600));
        state.recordAttempt(true, 0.3, Instant.now().minusSeconds(3600));
        when(learnerModel.skillStates(learner)).thenReturn(List.of(state));

        String digest = service.digest(learner,
                List.of(new TutorMemoryService.TopicRef(moles, "Moles")));
        assertThat(digest).contains("practiced 1 time(s), 1 correct");
        assertThat(digest).contains("earlier today");
        assertThat(digest).doesNotContain("earlier tutor ask");
        assertThat(digest).doesNotContain("spaced review");
    }

    @Test
    @DisplayName("anonymous preview and empty topic lists never digest")
    void anonymousAndEmptySafe() {
        assertThat(service.digest(null, List.of(new TutorMemoryService.TopicRef(moles, "Moles"))))
                .isNull();
        assertThat(service.digest(learner, List.of())).isNull();
        assertThat(service.digest(learner, null)).isNull();
    }

    @Test
    @DisplayName("the digest is capped: at most 3 topics and 700 chars")
    void digestBounded() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // the service scopes queries to the first MAX_TOPICS topics — the 4th
        // is never even looked up
        List<UUID> queried = List.of(a, b, c);
        when(engagements.findByLearnerIdAndNodeIdIn(learner, queried)).thenReturn(
                queried.stream().map(node -> ask(node, "TOPIC_ENGAGEMENT",
                        Instant.now().minusSeconds(24 * 3600))).toList());
        when(reviews.findByLearnerIdAndNodeIdInAndStatus(learner, queried,
                ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(learnerModel.skillStates(learner)).thenReturn(List.of());

        String digest = service.digest(learner, List.of(a, b, c, d).stream()
                .map(node -> new TutorMemoryService.TopicRef(node, "Topic " + node))
                .toList());

        // the 4th topic never renders — the block stays scannable
        assertThat(digest.lines()).hasSize(3);
        assertThat(digest.length()).isLessThanOrEqualTo(TutorMemoryService.MAX_BLOCK_CHARS);
        assertThat(digest).doesNotContain("Topic " + d);
    }
}
