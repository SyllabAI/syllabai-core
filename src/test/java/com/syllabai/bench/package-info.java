/**
 * T-C13 retrieval benchmark harness (spec: RETRIEVAL_BENCHMARK_HARNESS_SPEC.md,
 * RATIFIED v1.0 2026-09-17).
 *
 * <p><strong>Lane contract.</strong> This package is the measurement instrument
 * for the ADR-020 acceptance principle: no retrieval technique becomes a
 * production default without a recorded benchmark win on the frozen gold set
 * (MASTER_SPEC integration rule 12). It measures; it never serves. Nothing in
 * this package may change production behavior, and harness code has no DB or
 * network access — it runs entirely over the frozen snapshot (snap-001) and
 * frozen gold set (gold-v1), both SHA-256-verified fail-closed before scoring.</p>
 *
 * <p><strong>Layout (spec §6):</strong> {@link com.syllabai.bench.BenchSnapshot}
 * + {@link com.syllabai.bench.BenchGold} (fail-closed loaders),
 * {@link com.syllabai.bench.BenchGraph} (snapshot-backed stubs so the REAL
 * production retriever runs unmodified), {@link com.syllabai.bench.ArmA0}
 * (the production baseline arm), {@link com.syllabai.bench.BenchMetrics}
 * (pure deterministic scoring, formulas pinned to the Run-001 B-proxy
 * definitions), {@link com.syllabai.bench.Run002A0} (evidence writer:
 * results.json + RUN_REPORT.md + SHA256SUMS under evidence/bench-001/runs/).</p>
 *
 * <p><strong>Honesty rules (spec §1, §6).</strong> An arm with unmet
 * prerequisites reports UNAVAILABLE with the named missing row; an empty result
 * from a runnable arm is scored as a real zero; no clocks in scoring paths;
 * a run whose manifest cannot be reconstructed is discarded. Run reports must
 * carry the dual-denominator view (ALL vs VALIDATED-only) per §10 ruling 1.</p>
 */
package com.syllabai.bench;
