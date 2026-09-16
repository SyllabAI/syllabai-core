# InterventionRun — Persistence & Query-Cost Analysis (E2 §14)

**Status:** ANALYSIS COMPLETE (local, static + schema-verified against `V26__intervention_runs.sql` and the live service code at main `630b269` + the pending IT-fixture fix). No product code changed by this document. **This artifact closes the "persistence/query cost understood" precondition of contract §14; the OTHER §14 precondition (acceptance criteria demonstrated in CI) still requires one green core-ci run on the fixed tree — see the boundary section at the end.**

**Scope:** contract `INTERVENTION_RUN_PROTOTYPE.md` §14 ("Do not promote `InterventionRun` from PROPOSED to ACCEPTED production architecture until … the resulting persistence/query cost is understood") and the §10 normalization trigger ("normalize … as required by actual query patterns"). Analysis performed locally 2026-09-17; no hosted CI consumed (Actions minutes exhausted — 2,000/2,000).

## 1. Storage inventory (V26, exactly as deployed)

Three tables, execution-identity only (no learner-state duplication — E2 criteria 6/7):

| table | grain | fixed columns | jsonb columns | indexes |
|---|---|---|---|---|
| `intervention_run` | one row per run | 20 (incl. 4 timestamps, 3 status guards) | `target_specification_points`, `question_part_ids`, `evidence_refs`, `allowed_tool_ids` (all `default '[]'`) | PK + `(learner_id, created_at desc)` |
| `intervention_run_step` | one row per recorded step observation | 9 | — | PK + `unique (run_id, sequence_no)` (doubles as the retrieval index) |
| `intervention_run_evidence` | one row per attached evidence reference | 4 (`evidence_ref` capped varchar(512)) | — | PK + `(run_id, captured_at)` |

Estimated row width at pilot scale: run ≈ 1.0–1.5 KB (jsonb arrays carry 0–1 UUIDs and a 3-element tool-id list); step ≈ 200–300 B; evidence ≈ 150–250 B (refs like `attempt:<uuid>` ≈ 45 B). A complete E2 §11 scenario run (the IT's own trace) = 1 run + 1 step + 1 evidence row ≈ 1.7 KB total.

## 2. Actual query patterns (exhaustive — this is the entire access surface)

`InterventionRunRepository` declares **zero** custom methods; the service uses only `findById` (PK lookup) and `save`. Child repositories declare exactly one finder each:

| call site | query | index used |
|---|---|---|
| `InterventionRunService.find` (reconstruction, criterion 8) | `intervention_run` PK lookup | PK |
| `InterventionRunService.steps` | steps by `run_id` ordered by `sequence_no` | `intervention_run_step_unique_seq (run_id, sequence_no)` |
| `InterventionRunService.evidence` | evidence by `run_id` ordered by `captured_at` | `intervention_run_evidence_run_idx (run_id, captured_at)` |
| `InterventionRunScenarioService.createFromRecommendation` | `subjects.findByKnowledgeNodeId`, NBA `actionsFor`, `skillStates.findByLearnerIdAndNodeId` — all pre-existing paths; the run write itself is a single INSERT | — |
| resume/complete/attach guards | load by PK, mutate in place | PK |

Consequences, verified against the code (not inferred):

1. **Every query is run-scoped.** There is no cross-learner scan, no jsonb-content filter, no join between the three tables beyond the explicit per-run child fetch. The reconstruction path is 3 indexed point queries — O(1) in table size.
2. **No N+1 by construction.** Steps and evidence are fetched explicitly and only by `run_id`; nothing iterates runs to fetch children.
3. **The `(learner_id, created_at desc)` index is currently write-only overhead** — no reader uses it (learner-facing run history is a v2 surface, not built). Cost: one extra index entry per INSERT (~30 B). Acceptable; keep it so the first history endpoint does not require a migration.
4. **No query needs jsonb containment/GIN support.** `target_specification_points` is written and returned verbatim (criterion 2: immutable identity); nothing filters on its contents.

## 3. Growth model

Writes happen ONLY through `InterventionRunScenarioService.createFromRecommendation` (learner-paced: one run per explicit practice-scenario creation from the NBA surface) and the run's own lifecycle endpoints. There is no scheduled job, no evidence fan-out, no automatic retry that appends rows. Worst-case pilot growth (54 learners, one run per learner per day, ~4 steps + ~2 evidence rows each): ≈ 54 × 7 × 2 KB ≈ **0.75 MB/month** — four orders of magnitude below the existing attempt/answer/telemetry volume. Retention/partitioning is not triggered anywhere near pilot scale; the append-only terminal history (criterion 9) is enforced in the domain layer and costs nothing extra in storage.

## 4. §10 normalization trigger — assessment

The §10 rule: normalize the evidence/target relationships **as required by actual query patterns**. Measured against §2:

- **Evidence:** the `intervention_run_evidence` child table IS the normalized relationship (typed role + timestamp + ref, indexed by run). The `evidence_refs` jsonb column on the run row is vestigial redundancy: the service never writes it (runs are created with `'[]'`) and nothing reads it. It is a candidate for removal in a future migration — recorded here, deliberately NOT changed in this pass (no schema churn while CI minutes are exhausted; removal is a one-line V-migration when the next schema window opens).
- **Target specification points:** a 1-element UUID array, immutable by contract (criterion 2), never queried by content. Normalizing it would add a table and a join to save ~40 B — negative value at any foreseeable scale. §10's own cost-benefit language says leave it.
- **Steps:** already fully normalized (one row per observation, unique sequence).

**Verdict: the normalization trigger is NOT fired by actual query patterns.** The V26 shape is adequate for the pilot and for the first history endpoint.

## 5. §14 decision-gate position

§14 requires BOTH: (a) acceptance criteria satisfied, (b) persistence/query cost understood.

- (b) is now satisfied by this artifact (schema + code-verified, with the honest caveats above).
- (a) per the 2026-09-17 session-81 audit: criteria 1–10 are demonstrated by the unit suite (540 green in CI) and the live 10/10 production verification, BUT `InterventionRunFlowIT` is RED in CI on the two fixture defects. The fixtures are now corrected locally (this branch: deterministic-LOW_MASTERY fixture + subject-island anchoring; full local `mvn verify` green with ITs Docker-skipped, unit 540/540) — the CI demonstration requires **one green core-ci run on the fixed tree**.

**Therefore: E2 remains PROPOSED and the promotion decision remains the operator's. What this artifact changes is that the §14 cost precondition no longer blocks the decision — the sole remaining precondition is the authoritative CI run.** The §14 fallback clause ("retain the contract as a lightweight audit record rather than building a general workflow engine") is also affirmed by the cost data: the whole apparatus costs ~2 KB and 3 indexed point-queries per intervention — there is no overhead case for collapsing it.

## 6. Boundary of this analysis

- Static + schema analysis of the code at main `630b269` plus the local fixture-fix branch; no production or campaign database was touched, no hosted CI consumed.
- The growth model is a design-level estimate (bounded, worst-case, parametric in learner count) — consistent with the standard set in `EVIDENCE_STATE_TRANSITION_HARDENING.md`: design-level conclusions are labelled as such until a database-backed measurement exists. A `pg_stat_user_tables` snapshot after pilot r3 is the natural confirmation point and requires no code.
- The related but SEPARATE evidence-emission concurrency question stays `UNVERIFIED / PROPOSED` per `EVIDENCE_STATE_TRANSITION_HARDENING.md` (630b269) — this document does not touch it.
