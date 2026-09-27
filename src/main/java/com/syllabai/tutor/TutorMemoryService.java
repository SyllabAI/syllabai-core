package com.syllabai.tutor;

import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.TutorTopicEngagement;
import com.syllabai.learner.TutorTopicEngagementRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cross-session tutor memory (s140): a bounded, deterministic digest of what
 * the learner has ALREADY done on the topics of the current ask — earlier
 * tutor asks (V21 engagement), practice outcomes (BKT skill state) and the
 * forgetting-curve's review status — rendered as the RECENT LEARNING
 * EXPERIENCES block of the tutor prompt.
 *
 * <p>Design contract:</p>
 * <ul>
 *   <li>deterministic — three repository reads and string building, no LLM,
 *       no invented narrative: the MODEL weaves continuity from these facts,
 *       it never gets invented ones;</li>
 *   <li>matched-topic scoped — the digest answers "what have we done on
 *       THIS topic", never a dump of the learner's account (data
 *       minimization, §20);</li>
 *   <li>qualitative surface — counts and recency only; mastery
 *       probabilities stay in the LEARNER CONTEXT block (and are already
 *       barred from answers by the v1 system rule);</li>
 *   <li>absent history ⇒ no block at all — a fresh learner gets the exact
 *       pre-s140 prompt shape apart from the v5 system rule.</li>
 * </ul>
 */
@Component
public class TutorMemoryService {

    /** topics per digest — the intent matcher rarely surfaces more, and the
     *  block must stay scannable inside a 200-word answer budget. */
    static final int MAX_TOPICS = 3;
    static final int MAX_BLOCK_CHARS = 700;

    /** A matched topic the digest is built for (title for the model, node for
     *  the signal joins). Local record keeps the service testable without KG
     *  types. */
    public record TopicRef(UUID nodeId, String title) {
    }

    private final TutorTopicEngagementRepository engagements;
    private final ReviewScheduleRepository reviews;
    private final LearnerModelService learnerModel;

    public TutorMemoryService(TutorTopicEngagementRepository engagements,
                              ReviewScheduleRepository reviews,
                              LearnerModelService learnerModel) {
        this.engagements = engagements;
        this.reviews = reviews;
        this.learnerModel = learnerModel;
    }

    /**
     * The digest for this ask, or null when the learner has no prior
     * engagement, practice or review signal on any matched topic.
     *
     * @param learnerId asking learner (null = anonymous preview ⇒ no digest)
     * @param topics    the deterministic intent matcher's matched topics,
     *                  most specific first (input order preserved, capped)
     */
    public String digest(UUID learnerId, List<TopicRef> topics) {
        if (learnerId == null || topics == null || topics.isEmpty()) {
            return null;
        }
        List<TopicRef> scoped = topics.stream().limit(MAX_TOPICS).toList();
        List<UUID> nodeIds = scoped.stream().map(TopicRef::nodeId).toList();

        Map<UUID, List<TutorTopicEngagement>> asksByNode = new HashMap<>();
        for (TutorTopicEngagement engagement
                : engagements.findByLearnerIdAndNodeIdIn(learnerId, nodeIds)) {
            asksByNode.computeIfAbsent(engagement.nodeId(), k -> new ArrayList<>())
                    .add(engagement);
        }
        Map<UUID, ReviewSchedule> pendingReviews = new HashMap<>();
        for (ReviewSchedule review : reviews.findByLearnerIdAndNodeIdInAndStatus(
                learnerId, nodeIds, ReviewSchedule.Status.PENDING)) {
            pendingReviews.putIfAbsent(review.nodeId(), review);
        }
        Map<UUID, SkillState> skills = new HashMap<>();
        for (SkillState state : learnerModel.skillStates(learnerId)) {
            if (nodeIds.contains(state.nodeId())) {
                skills.putIfAbsent(state.nodeId(), state);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (TopicRef topic : scoped) {
            String line = topicLine(topic, asksByNode.get(topic.nodeId()),
                    skills.get(topic.nodeId()), pendingReviews.containsKey(topic.nodeId()));
            if (line == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            if (sb.length() >= MAX_BLOCK_CHARS) {
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** One topic's fact line, or null when the learner has no signal on it. */
    private static String topicLine(TopicRef topic, List<TutorTopicEngagement> asks,
                                    SkillState skill, boolean reviewPending) {
        if (asks == null && skill == null && !reviewPending) {
            return null;
        }
        StringBuilder line = new StringBuilder("- '").append(topic.title()).append("':");
        boolean practiceOnly = asks == null || asks.isEmpty();
        if (!practiceOnly) {
            int doubtish = 0;
            Instant lastAsk = null;
            for (TutorTopicEngagement ask : asks) {
                String signal = ask.signalType();
                if ("DOUBT_SIGNAL".equals(signal) || "MISCONCEPTION_RELATED".equals(signal)) {
                    doubtish++;
                }
                if (lastAsk == null || ask.occurredAt().isAfter(lastAsk)) {
                    lastAsk = ask.occurredAt();
                }
            }
            line.append(' ').append(asks.size()).append(" earlier tutor ask(s), the last ")
                    .append(ago(lastAsk));
            if (doubtish * 2 > asks.size()) {
                line.append(" (mostly doubt-checks)");
            }
        }
        if (skill != null && skill.attempts() > 0) {
            line.append(practiceOnly ? "" : ";")
                    .append(" practiced ").append(skill.attempts()).append(" time(s), ")
                    .append(skill.correctCount()).append(" correct (last practice ")
                    .append(ago(skill.lastPracticedAt())).append(')');
        }
        if (reviewPending) {
            line.append(practiceOnly && (skill == null || skill.attempts() == 0) ? "" : ";")
                    .append(" due for a spaced review (mastery decayed)");
        }
        return line.toString();
    }

    /** honest coarse recency — the model needs "how fresh", not a timestamp */
    private static String ago(Instant at) {
        if (at == null) {
            return "at an unknown time";
        }
        Duration since = Duration.between(at, Instant.now());
        if (since.isNegative() || since.toHours() < 1) {
            return "earlier today";
        }
        if (since.toHours() < 24) {
            return "earlier today";
        }
        long days = since.toDays();
        if (days == 1) {
            return "yesterday";
        }
        if (days < 14) {
            return days + " days ago";
        }
        if (days < 60) {
            return (days / 7) + " weeks ago";
        }
        return "about " + Math.max(1, days / 30) + " months ago";
    }
}
