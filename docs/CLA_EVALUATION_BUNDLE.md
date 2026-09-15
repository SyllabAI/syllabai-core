# CLA evaluation bundle — §10.4 empirical quality gate

**Status:** VERIFIED (44/44 checks green) — 2026-09-15, production deployment of
`6a89815` lineage (CI run 34970016257 GREEN: full IT suite incl. LIM signal-
precedence and serving-surface performance guards, so contract §9.5 holds).
**Contract basis:** CLA contract §9 (evaluation hooks) and §10.4 (evaluation
bundle + promotion decision per ADR-020 benchmark discipline).
**Material:** real validated 4CH1 content served by production — 28 VALIDATED
topics / 194 VALIDATED subtopics over curriculum version 4CH1-2017 (Edexcel
International GCSE Chemistry), 34 servable STRUCTURED questions from the
ingested paper. Harness: `cla_eval_bundle.py` (phased; state machine persists
per-check results so every run is auditable).

## What the gate measures

| Set | Metric (contract §9 / ADR-020) | Result |
|-----|-------------------------------|--------|
| S-A | context resolution: stratified VALIDATED topics + a VALIDATED SUBTOPIC resolve; SUGGESTED CONCEPT node, unknown id, cross-subject pairing (CHM topic × 4CH1 root), and an UNVALIDATED topic (CHM topic × CHM root — live §1.2 validation gate) fail closed; unknown mode / blank question → 400 | **15/15 (100%)** |
| S-B | grounded precision per mode: every served answer non-refused, evidence-backed (evidenceCount ≥ 1), citations structurally valid, deterministic spec-anchor citation present, zero unresolvable `[n]` citation markers (unsupported-claim proxy) | **8/8 topics, 100%** |
| S-B | mode-specific correctness: context.mode echoed per response; when sources are rich EXPLAIN teaches; SUMMARIZE spans the anchored spec structure (≥2 distinct sources cited) | **8/8** |
| S-B | false-refusal rate on in-scope probes | **0** |
| S-C | refusal correctness on out-of-corpus probes (biology / physics / humanities / non-academic) + mark-scheme phishing through HINT: honest deterministic-or-grounded decline, zero fabricated citation markers | **5/5 (100%)** |
| S-D | §7 answer-leakage gate: HINT pre-attempt 200 with attempted=false and zero MARK_SCHEME citations; CHECK pre-attempt deterministic 409 attempt_required before generation; real attempt unlocks CHECK (attempted=true, grounded incl. the question's own scheme points); HINT stays scaffolding-only post-attempt (differential: no scheme-point content CHECK legitimately exposes appears in any HINT) | **10/10, leakage 0** |
| S-E | latency/cost observation (ADR-020): p50 EXPLAIN 4.35s, SUMMARIZE 4.61s, HINT 5.15s, CHECK 4.81s; max 6.23s; model openai/gpt-oss-120b via groq; 26 recorded LLM asks; 0 provider retries in the canonical run | recorded |
| S-F | no-regression: CI run 34970016257 GREEN on the evaluated lineage | GREEN |

## Findings the gate produced (and their fixes)

The gate was run to find defects, and it found two. Both were fixed in the
CLA slice itself — the gate was never relaxed to pass.

1. **Bare-anchor evidence (fixed in `d1f3586`).** The first live run showed
   that for most 4CH1 topics the only fused evidence was the bare topic title
   (the validated notes corpus covers ~one topic). SUMMARIZE honestly declined
   ("cannot summarize a title") and EXPLAIN varied between teaching from priors
   and declining — correct refusal discipline, unusable surface. Fix: the
   resolved topic's DIRECT VALIDATED subtopic learning outcomes (the actual
   specification statements — deterministic curriculum truth, 3-12 per topic)
   now join the evidence as provenance-bearing KNOWLEDGE_NODE items, code-
   ordered, bounded (SPEC_STRUCTURE_LIMIT=4, leaving the cap room for
   validated chunks). SUGGESTED/UNVALIDATED children are invisible (§1.2 gate
   parity); attribution stays on the resolved anchor (LIM/telemetry/NBA
   unchanged).
2. **SUMMARIZE scope drift (fixed in `6a89815`).** The deterministic
   SUMMARIZE plan did not demand full-scope coverage, so the model sometimes
   compressed only the first source cluster of a multi-statement spec
   structure. The plan now explicitly requires every provided specification
   statement to appear in the summary with citations per covered group.

Measurement notes (honesty record):
- The harness initially compared EXPLAIN vs SUMMARIZE answer LENGTH as a
  "compression" proxy. That is a category error — the two modes answer
  different scopes (learner's narrow question vs full spec digest) and their
  lengths are not commensurable. The shipped gate measures mode SHAPE
  (taught EXPLAIN, spec-spanning SUMMARIZE) instead; the raw per-topic lengths
  remain in the results JSON for inspection.
- Citation markers are matched for both ASCII `[n]` and fullwidth `【n】`
  (the model mixes bracket styles); unresolvable markers of either style fail
  the gate.
- Semantic adequacy of prose (is the explanation *good*) is not deterministically
  measurable; the bundle pins grounding, leakage, refusal, resolution and
  mode discipline, and the full per-topic answers are preserved verbatim in
  `evidence/cla_eval_bundle_results.json` for human audit.
- Transient upstream 5xx (provider rate window) are retried at most 3 times
  with 25s backoff and the retry count is recorded; resolution/refusal
  decisions themselves are deterministic endpoints and are never retried
  into passing.

## Promotion decision (§10.4 / ADR-020)

The learner-facing CLA surface (KG_TOPIC + PAST_PAPER_QUESTION contexts;
EXPLAIN / SUMMARIZE / HINT / CHECK modes) is promoted from "verified
implementation" to **empirically VERIFIED** against real validated 4CH1
material: grounded precision 100%, refusal correctness 100%, citation
validation 100% (zero unresolvable markers across all served answers),
context resolution 100%, answer leakage 0, false refusals 0, serving-surface
and LIM suites green. The surface may be exposed to learners (web panel) and
extended to the next contract-defined context kind, carrying this bundle as
the baseline; any future retrieval/reranking technique change (ADR-020) must
re-run this bundle and beat or match it.
