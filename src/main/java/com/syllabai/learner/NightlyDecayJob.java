package com.syllabai.learner;

import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * effective mastery crosses the review threshold. Every decay write and every
 * scheduled review is also published as a domain event so the research module can
 * log DECAY_APPLIED / REVIEW_SCHEDULED telemetry (§18). Disabled by default; enable
 * in the production profile via {@code syllabai.learner.decay-job.enabled=true}.</p>
 */
@Component
public class NightlyDecayJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyDecayJob.class);

    private final SkillStateRepository skillStates;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;
    private final ApplicationEventPublisher events;

    public NightlyDecayJob(SkillStateRepository skillStates,
                           ReviewScheduleRepository reviewSchedules,
                           EbbinghausDecayService decayService,
                           LearnerProperties properties,
                           ApplicationEventPublisher events) {
        this.skillStates = skillStates;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.properties = properties;
        this.events = events;
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
                double priorMastery = state.mastery();
                double effective = decayService.decayed(
                        priorMastery, state.lastPracticedAt(), now, params);
                // The review decision evaluates the effective (already decayed) mastery;
                // re-running the decay formula on the stored value would double-count.
                boolean reviewThresholdCrossed = effective < params.reviewBelow();
                if (effective < priorMastery) {
                    state.applyDecay(effective, now);
                    decayed++;
                    events.publishEvent(new DecayAppliedEvent(
                            state.learnerId(), state.nodeId(), priorMastery, effective,
                            Duration.between(state.lastPracticedAt(), now).toDays(),
                            (int) params.tauFor(priorMastery).toDays(),
                            reviewThresholdCrossed, now));
                }
                if (reviewThresholdCrossed
                        && !reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                                state.learnerId(), state.nodeId(), ReviewSchedule.Status.PENDING)) {
                    reviewSchedules.save(new ReviewSchedule(
                            state.learnerId(), state.nodeId(), now,
                            ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, effective));
                    reviewsScheduled++;
                    events.publishEvent(new ReviewScheduledEvent(
                            state.learnerId(), state.nodeId(), now, effective,
                            ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD.name(), now));
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
