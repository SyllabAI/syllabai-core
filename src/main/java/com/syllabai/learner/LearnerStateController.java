package com.syllabai.learner;

import com.syllabai.learner.decay.DecayParams;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.learner.dto.MisconceptionStateView;
import com.syllabai.learner.dto.SkillStateView;
import com.syllabai.identity.CurrentUserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-state read model (Master Spec §22: GET /api/v1/learners/me/state).
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class LearnerStateController {

    private final LearnerModelService learnerModel;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;

    public LearnerStateController(LearnerModelService learnerModel,
                                  ReviewScheduleRepository reviewSchedules,
                                  EbbinghausDecayService decayService,
                                  LearnerProperties properties) {
        this.learnerModel = learnerModel;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.properties = properties;
    }

    @GetMapping("/state")
    public LearnerStateView state(@CurrentUserId UUID learnerId) {
        Instant now = Instant.now();
        DecayParams decayParams = properties.decay().toParams();

        List<SkillStateView> skills = learnerModel.skillStates(learnerId).stream()
                .map(s -> {
                    double effective = decayService.decayed(
                            s.mastery(), s.lastPracticedAt(), now, decayParams);
                    return new SkillStateView(
                            s.nodeId(), s.mastery(), effective, bandOf(effective, decayParams),
                            s.attempts(), s.correctCount(), s.lastPracticedAt());
                })
                .toList();

        List<MisconceptionStateView> misconceptions = learnerModel.misconceptionStates(learnerId).stream()
                .map(m -> new MisconceptionStateView(
                        m.misconceptionNodeId(), m.probability(),
                        m.probability() >= properties.bdt().activeThreshold(),
                        m.evidenceCount(), m.lastEvidenceAt()))
                .toList();

        List<LearnerStateView.ReviewView> reviews = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING)
                .stream()
                .map(r -> new LearnerStateView.ReviewView(
                        r.nodeId(), r.dueAt(), r.reason().name()))
                .toList();

        return new LearnerStateView(learnerId, skills, misconceptions, reviews);
    }

    private String bandOf(double mastery, DecayParams params) {
        if (mastery < params.lowBandCeiling()) {
            return "LOW";
        }
        if (mastery < params.highBandFloor()) {
            return "DEVELOPING";
        }
        return "SECURE";
    }
}
