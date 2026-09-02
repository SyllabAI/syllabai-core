package com.syllabai.learner;

import com.syllabai.learner.decay.EbbinghausDecayService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly forgetting-decay batch (Master Spec §11 "forgetting decay applied nightly",
 * §31 job APPLY_FORGETTING_DECAY, backlog F-159).
 *
 * <p>For every skill state idle beyond the grace period, mastery is decayed
 * (P(t)=P₀·e^(−t/τ), τ by proficiency band) and a review is scheduled when the
 * effective mastery crosses the review threshold. Disabled by default; enable in
 * the production profile via {@code syllabai.learner.decay-job.enabled=true}.</p>
 */
@Component
public class NightlyDecayJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyDecayJob.class);

    private final SkillStateRepository skillStates;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;

    public NightlyDecayJob(SkillStateRepository skillStates,
                           ReviewScheduleRepository reviewSchedules,
                           EbbinghausDecayService decayService,
                           LearnerProperties properties) {
        this.skillStates = skillStates;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.properties = properties;
    }

    @Scheduled(cron = "${syllabai.learner.decay-job.cron:0 0 3 * * *}")
    @Transactional
    public void applyForgettingDecay() {
        if (!properties.decayJob().enabled()) {
            log.debug("decay job disabled (syllabai.learner.decay-job.enabled=false)");
            return;
        }
        Instant now = Instant.now();
        Instant idleSince = now.minus(properties.decayJob().idleGracePeriod());
        var params = properties.decay().toParams();

        int decayed = 0;
        int reviewsScheduled = 0;
        Pageable page = PageRequest.of(0, 500);
        var candidates = skillStates.findByLastPracticedAtBefore(idleSince, page);
        while (!candidates.isEmpty()) {
            for (SkillState state : candidates) {
                double effective = decayService.decayed(
                        state.mastery(), state.lastPracticedAt(), now, params);
                if (effective < state.mastery()) {
                    state.applyDecay(effective, now);
                    decayed++;
                }
                if (decayService.needsReview(state.mastery(), state.lastPracticedAt(), now, params)
                        && !reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                                state.learnerId(), state.nodeId(), ReviewSchedule.Status.PENDING)) {
                    reviewSchedules.save(new ReviewSchedule(
                            state.learnerId(), state.nodeId(), now,
                            ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, effective));
                    reviewsScheduled++;
                }
            }
            skillStates.saveAll(candidates);
            page = page.next();
            candidates = skillStates.findByLastPracticedAtBefore(idleSince, page);
        }
        log.info("forgetting-decay batch complete: {} states decayed, {} reviews scheduled",
                decayed, reviewsScheduled);
    }
}
