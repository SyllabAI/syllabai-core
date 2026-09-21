package com.syllabai.learner;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The decay-run ledger (V38). {@link JpaRepository#existsById} is the
 * run-if-missed guard: has this 03:00 UTC window already been handled —
 * including nights that legitimately decayed zero cells?
 */
public interface DecayJobRunRepository extends JpaRepository<DecayJobRun, Instant> {
}
