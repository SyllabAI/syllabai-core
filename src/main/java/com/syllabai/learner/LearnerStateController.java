package com.syllabai.learner;

import com.syllabai.learner.decay.DecayParams;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.learner.dto.MisconceptionStateView;
import com.syllabai.learner.dto.SkillStateView;
import com.syllabai.learner.dto.FlashcardRatingView;
import com.syllabai.learner.dto.NoteVoteView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-state read model (Master Spec §22: GET /api/v1/learners/me/state and
 * GET /api/v1/learners/me/knowledge-graph).
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class LearnerStateController {

    private final LearnerModelService learnerModel;
    private final LearnerKnowledgeGraphService graphs;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final TutorEngagementReader tutorEngagements;
    private final TutorTopicEngagementRepository engagements;
    private final FlashcardRatingRepository flashcardRatings;
    private final NoteVoteRepository noteVotes;

    /** the learner-state rating window (same recency posture as tutor asks) */
    private static final int FLASHCARD_RATING_LIMIT = 50;

    /** the learner-state vote window — the same recency posture as ratings */
    private static final int NOTE_VOTE_LIMIT = 50;

    public LearnerStateController(LearnerModelService learnerModel,
                                  LearnerKnowledgeGraphService graphs,
                                  ReviewScheduleRepository reviewSchedules,
                                  EbbinghausDecayService decayService,
                                  LearnerProperties properties,
                                  KnowledgeNodeRepository knowledgeNodes,
                                  TutorEngagementReader tutorEngagements,
                                  TutorTopicEngagementRepository engagements,
                                  FlashcardRatingRepository flashcardRatings,
                                  NoteVoteRepository noteVotes) {
        this.learnerModel = learnerModel;
        this.graphs = graphs;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.properties = properties;
        this.knowledgeNodes = knowledgeNodes;
        this.tutorEngagements = tutorEngagements;
        this.engagements = engagements;
        this.flashcardRatings = flashcardRatings;
        this.noteVotes = noteVotes;
    }

    @GetMapping("/state")
    public LearnerStateView state(@CurrentUserId UUID learnerId) {
        Instant now = Instant.now();
        DecayParams decayParams = properties.decay().toParams();

        var skills = learnerModel.skillStates(learnerId);
        var misconceptions = learnerModel.misconceptionReadings(learnerId);
        var reviews = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING);

        // P1 pilot UX: resolve human node titles for every id this read model
        // exposes, so clients never have to render raw UUIDs. One batched
        // findAllById for all three collections (a learner has few rows); a
        // missing node row yields null and clients keep their own fallback.
        Set<UUID> nodeIds = new HashSet<>();
        skills.forEach(s -> nodeIds.add(s.nodeId()));
        misconceptions.forEach(r -> nodeIds.add(r.state().misconceptionNodeId()));
        reviews.forEach(r -> nodeIds.add(r.nodeId()));
        // V21 (P7): what the learner has been asking the Tutor about (last 30
        // days, top 10 topics) — structured engagement signal, chat text stays
        // in the research log. Resolved BEFORE the titles map so engaged topics
        // get human titles too (they are rarely in skills/misconceptions).
        List<TutorTopicEngagement> recentAsks = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, now.minus(java.time.Duration.ofDays(30)));
        recentAsks.forEach(e -> nodeIds.add(e.nodeId()));
        Map<UUID, String> titles = nodeIds.isEmpty() ? Map.of()
                : knowledgeNodes.findAllById(nodeIds).stream()
                        .collect(Collectors.toMap(KnowledgeNode::id,
                                KnowledgeNode::title, (a, b) -> a));

        List<SkillStateView> skillViews = skills.stream()
                .map(s -> {
                    double effective = decayService.decayed(
                            s.mastery(), s.lastPracticedAt(), now, decayParams);
                    return new SkillStateView(
                            s.nodeId(), s.mastery(), effective, bandOf(effective, decayParams),
                            s.attempts(), s.correctCount(), s.lastPracticedAt(),
                            s.proceduralFluencyGap(), titles.get(s.nodeId()));
                })
                .toList();

        List<MisconceptionStateView> misconceptionViews = misconceptions.stream()
                // MED-2/ADR-032: the learner-facing view shows the staleness-relaxed
                // probability (JSON shape unchanged; probability is the relaxed value)
                .map(r -> new MisconceptionStateView(
                        r.state().misconceptionNodeId(), r.effective(),
                        r.effective() >= properties.bdt().activeThreshold(),
                        r.evidenceCount(), r.state().lastEvidenceAt(),
                        titles.get(r.state().misconceptionNodeId())))
                .toList();

        List<LearnerStateView.ReviewView> reviewViews = reviews
                .stream()
                .map(r -> new LearnerStateView.ReviewView(
                        r.nodeId(), r.dueAt(), r.reason().name(), titles.get(r.nodeId())))
                .toList();

        // V21 (P7): grouped engagement summary from the pre-fetched recent asks
        List<LearnerStateView.TutorEngagementView> engagementViews = tutorEngagements
                .groupEngagementSummary(recentAsks, 10, titles::get);

        // V47 (tranche 4.4): flashcard rating events — the self-report evidence
        // class (append-only trail, newest first). Exposure / history / stats
        // ONLY: deliberately absent from skillStates, which stays the marked-
        // attempt mastery record (honesty rule pinned by FlashcardRatingFlowIT).
        List<FlashcardRatingView> flashcardViews = flashcardRatings
                .findByLearnerIdOrderByOccurredAtDesc(learnerId,
                        PageRequest.of(0, FLASHCARD_RATING_LIMIT))
                .stream()
                .map(r -> FlashcardRatingView.from(r, null))
                .toList();

        // V48 (tranche 4.9): note-vote events — the self-report evidence class
        // (append-only trail, newest first). Exposure / history / stats ONLY,
        // same honesty ruling as ratings: deliberately absent from skillStates,
        // which stays the marked-attempt mastery record (pinned by
        // NoteVoteFlowIT).
        List<NoteVoteView> noteVoteViews = noteVotes
                .findByLearnerIdOrderByOccurredAtDesc(learnerId,
                        PageRequest.of(0, NOTE_VOTE_LIMIT))
                .stream()
                .map(v -> NoteVoteView.from(v, null))
                .toList();

        return new LearnerStateView(learnerId, skillViews, misconceptionViews, reviewViews,
                engagementViews, flashcardViews, noteVoteViews);
    }

    /**
     * The learner's personalized knowledge graph (F-034): the curriculum tree
     * under {@code rootId} (a subject's KG root, resolvable via
     * GET /api/v1/curriculum/subjects) annotated with proficiency, misconception
     * and review state, plus the drawable prerequisite edges. One call replaces
     * the client-side tree + state join.
     */
    @GetMapping("/knowledge-graph")
    public LearnerKnowledgeGraphView knowledgeGraph(@CurrentUserId UUID learnerId,
                                                    @RequestParam UUID rootId) {
        return graphs.graphFor(learnerId, rootId);
    }

    private String bandOf(double mastery, DecayParams params) {
        return params.bandOf(mastery);
    }
}
