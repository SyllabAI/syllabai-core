package com.syllabai.learner;

import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 *
 * <p><b>Run-if-missed trigger (session-114).</b> The batch used to fire on a
 * single in-process cron tick at 03:00 UTC — on the Render free tier the JVM
 * sleeps straight through that tick whenever nothing keeps it awake (measured:
 * Sept 17-21, five consecutive missed nights, keep-alive included). The trigger
 * is now a short-interval checker ({@code syllabai.learner.decay-job.check-cron},
 * default every 15 min): each tick asks "has the current 03:00 UTC window run
 * yet?" (the V38 {@code decay_job_runs} ledger — which also records zero-cell
 * runs, so a quiet night is never mistaken for a missed one) and runs the batch
 * when it hasn't. Any wake therefore completes the nightly promise; the math was
 * already self-healing (decay is anchored at {@code last_practiced_at} and
 * recomputed with the full elapsed delta), so a late run is exact, not
 * approximate. Spring's default scheduler is single-threaded and the free tier
 * runs one instance, so ticks cannot overlap; if instances ever race a window,
 * the ledger's primary key fails the loser's transaction and its idempotent
 * writes roll back (C-4 posture unchanged).</p>
 *
 * <p>Conflict posture (C-4): {@code skill_states} rows carry an optimistic-lock
 * version. If a learner submits evidence while this batch holds the same row, the
 * batch transaction fails at flush and rolls back as a whole — the decay is
 * recomputed from the stored (still pre-decay) state on the next tick, so a lost
 * run self-heals and no decay is ever applied twice.</p>
 */
@Component
public class NightlyDecayJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyDecayJob.class);

    /** how far past the window anchor a tick may run and still count as SCHEDULED */
    static final Duration SCHEDULED_GRACE = Duration.ofMinutes(5);

    private final SkillStateRepository skillStates;
    private final ReviewScheduleRepository reviewSchedules;
    private final DecayJobRunRepository decayJobRuns;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;
    private final ApplicationEventPublisher events;

    public NightlyDecayJob(SkillStateRepository skillStates,
                           ReviewScheduleRepository reviewSchedules,
                           DecayJobRunRepository decayJobRuns,
                           EbbinghausDecayService decayService,
                           LearnerProperties properties,
                           ApplicationEventPublisher events) {
        this.skillStates = skillStates;
        this.reviewSchedules = reviewSchedules;
        this.decayJobRuns = decayJobRuns;
        this.decayService = decayService;
        this.properties = properties;
        this.events = events;
    }

    /**
     * The checker: every 15 minutes, run the decay batch iff the current
     * 03:00 UTC window has not run yet (ledger row absent). On an awake night
     * the 03:00 tick runs it (trigger SCHEDULED); after a missed night the
     * first tick past the window completes it (trigger CATCH_UP).
     */
    @Scheduled(cron = "${syllabai.learner.decay-job.check-cron:0 */15 * * * *}")
    @Transactional
    public void applyForgettingDecay() {
        if (!properties.decayJob().enabled()) {
            log.debug("decay job disabled (syllabai.learner.decay-job.enabled=false)");
            return;
        }
        Instant now = Instant.now();
        Instant windowStart = windowStart(now, properties.decayJob().windowHourUtc());
        if (decayJobRuns.existsById(windowStart)) {
            log.debug("decay window {} already handled — nothing to do", windowStart);
            return;
        }
        String trigger = Duration.between(windowStart, now).compareTo(SCHEDULED_GRACE) <= 0
                ? "SCHEDULED" : "CATCH_UP";
        int[] outcome = runDecayPass(now);
        decayJobRuns.save(new DecayJobRun(
                windowStart, now, trigger, outcome[0], outcome[1]));
        log.info("forgetting-decay batch complete: {} states decayed, {} reviews scheduled "
                        + "(window {}, trigger {}, executed {})",
                outcome[0], outcome[1], windowStart, trigger, now);
    }

    /**
     * The most recent {@code windowHourUtc}:00 UTC instant at or before {@code now}.
     * Before 03:00 UTC the current window is yesterday's (already handled by its
     * own ticks); from 03:00 UTC it is today's.
     */
    static Instant windowStart(Instant now, int windowHourUtc) {
        ZonedDateTime zoned = now.atZone(ZoneOffset.UTC);
        ZonedDateTime todayWindow = LocalDate.of(
                zoned.getYear(), zoned.getMonth(), zoned.getDayOfMonth())
                .atTime(windowHourUtc, 0).atZone(ZoneOffset.UTC);
        return zoned.isBefore(todayWindow)
                ? todayWindow.minusDays(1).toInstant()
                : todayWindow.toInstant();
    }

    /** the decay pass itself — paging, event publishing and review dedup unchanged */
    private int[] runDecayPass(Instant now) {
        Instant idleSince = now.minus(properties.decayJob().idleGracePeriod());
        var params = properties.decay().toParams();

        int decayed = 0;
        int reviewsScheduled = 0;
        // Stable sort is mandatory here: the batch mutates the rows it pages over
        // (applyDecay rewrites mastery/updatedAt in the same transaction), and an
        // unsorted LIMIT/OFFSET scan can revisit already-decayed rows (compounding
        // the decay) or skip others once Postgres relocates the updated tuples.
        // The sort key (lastPracticedAt, id) is itself left untouched by applyDecay,
        // so the ordering is stable across pages.
        Pageable page = PageRequest.of(0, 500,
                Sort.by(Sort.Direction.ASC, "lastPracticedAt", "id"));
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
        return new int[]{decayed, reviewsScheduled};
    }
}
