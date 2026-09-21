package com.syllabai.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per executed forgetting-decay run (V38 ledger). The nightly batch's
 * run-if-missed checker (session-114) needs to know whether the current UTC
 * window already ran — including nights that legitimately decayed zero cells —
 * and the pilot's t1 protocol needs the actual execution dates (session-109
 * finding: "t1 must pin actual decay dates"). This ledger is both.
 *
 * <p>Append-only by convention: a window is never re-run while its row exists
 * ({@code NightlyDecayJob} checks {@code existsById(windowStart)} before
 * running), so each {@code window_start} is written at most once. The primary
 * key doubles as the concurrency guard — should two instances ever race the
 * same window, the second insert fails its transaction and its (mathematically
 * idempotent) decay writes roll back with it.</p>
 */
@Entity
@Table(name = "decay_job_runs")
public class DecayJobRun {

    /** the 03:00 UTC window anchor this run belongs to (the scheduled fire time) */
    @Id
    @Column(name = "window_start")
    private Instant windowStart;

    /** when the run actually fired (== windowStart for an on-time SCHEDULED run) */
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /** SCHEDULED (on the window tick) | CATCH_UP (a late wake completing a missed window) */
    @Column(name = "trigger_kind", nullable = false, length = 20)
    private String triggerKind;

    @Column(name = "decayed", nullable = false)
    private int decayed;

    @Column(name = "reviews_scheduled", nullable = false)
    private int reviewsScheduled;

    protected DecayJobRun() {
    }

    public DecayJobRun(Instant windowStart, Instant executedAt, String triggerKind,
                       int decayed, int reviewsScheduled) {
        this.windowStart = windowStart;
        this.executedAt = executedAt;
        this.triggerKind = triggerKind;
        this.decayed = decayed;
        this.reviewsScheduled = reviewsScheduled;
    }

    public Instant windowStart() {
        return windowStart;
    }

    public Instant executedAt() {
        return executedAt;
    }

    public String triggerKind() {
        return triggerKind;
    }

    public int decayed() {
        return decayed;
    }

    public int reviewsScheduled() {
        return reviewsScheduled;
    }
}
