# InterventionRun (E2) — claim-by-claim acceptance record

**Contract:** `SyllabAI/syllabai/docs/research/INTERVENTION_RUN_PROTOTYPE.md`
(central `021ab89`, Status: PROPOSED — this prototype is the contract's own
sanctioned path toward ACCEPTED, §14 decision gate) + `syllabai-core` issue #19.
**Scope:** backend orchestration boundary only; no graph UI, no workflow
engine, no learner-model change.

**Implementation provenance:** the domain layer (aggregate, status machine,
repositories, service core, V26 schema, first unit tests) was implemented by a
concurrent lane on main (`4cb01b6`..`190f683`, 2026-09-15); this session
composed the remaining acceptance surface on top of it — recommendation-backed
creation (scenario), the learner API boundary, terminal-evidence freeze, the
flow IT and this record. Nothing the lane built was rewritten or discarded.

**Verification:** unit suite **540 green / 0 failures / 0 errors / 1 known
pre-existing skip** (`DatabaseIsolationGuardTest`) at core `02643ed` —
**confirmed inside real GitHub Actions CI** (run `35078454055`, not only
locally); **live verification 10/10 PASS on production** at core `02643ed`
(`evidence/intervention/intervention_run_live_verification_02643ed.log`: auth
gate, deterministic NBA cold-start PRACTICE, run-from-recommendation snapshot
identity, lifecycle + reconstruction, mutation boundary with zero skill rows,
terminal-immutability 409s, NAMED resume-mismatch 409, ownership 404).

**First real Docker-backed CI execution (2026-09-16, discovered in the
2026-09-17 reconciliation):** Actions quota recovered mid-day on 2026-09-16 and
the push runs for `d75a0e3`/`d330a3a`/`02643ed` (runs `35075687695`,
`35077858230`, `35078454055`) executed the full Docker IT suite for the first
time (68 ITs: 62 green, 6 red — identical failure sets across all three
runs). `InterventionRunFlowIT` ran for the FIRST time here: **2/2 flows red,
both classified HARNESS (fixture) defects, zero product defects, zero gates
weakened** — (1) `recommendationToRunToTerminalReconstruction`: the
deterministic NBA's first-rank action for the fixture's fresh two-zero-attempt
state is `RETRY_PROBLEM_QUESTION`/`PROBLEM_QUESTION` on the ingestion anchor,
not the `PRACTISE_QUESTIONS`/`LOW_MASTERY` the fixture assumed — the policy
behaved deterministically; the fixture encoded the wrong expectation;
(2) `resumeVersionGateOwnershipAndColdStart`: the paper-ingestion path creates
the Subject row for the draft's subject WITHOUT a KG root and anchors the
paper to a standalone ingestion-anchor node, so the anchor is not any
subject's `knowledgeNodeId` and `createFromRecommendation` fail-closes 404
(`subject for knowledge root … not found`) — correct product fail-closed
behavior against a fixture that assumed the anchor IS a subject root.
Both live-found-equivalent findings are recorded here honestly; the fixtures
need the fix, the domain layer does not.

Status vocabulary: PROPOSED | ACCEPTED | IMPLEMENTED | VERIFIED | INFERRED |
REPORTED | UNVERIFIED | REJECTED.

## Acceptance criteria (contract §12) — claim-by-claim

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Run created from a recommendation/diagnosis snapshot | ACCEPTED (implemented) | `InterventionRunScenarioService.createFromRecommendation`: the run's diagnosis snapshot references the deterministic NBA output (`nba:<policy>:<root>:<target>:rank<n>:<reason>`), `diagnosisVersion` = the NBA policy version, `origin=NBA`; unit-tested with fakes + `InterventionRunFlowIT.recommendationToRunToTerminalReconstruction` |
| 2 | Immutable target/evidence/intervention identity | ACCEPTED (implemented) | target SP list, evidence refs, intervention version + 64-hex stable hash (SHA-256 over version+action+tools, lane's `hashIntervention`) are written at creation; the aggregate exposes no mutators for identity fields (lane's `InterventionRun`); entity columns not updatable after persist |
| 3 | Ordered step observations | ACCEPTED (implemented) | `intervention_run_step` with `unique(run_id, sequence_no)` + monotonic-sequence domain guard (`recordStep` throws on non-increasing sequence); server assigns the next sequence; `stepsOf` returns `OrderBySequenceNoAsc` |
| 4 | No resume after intervention hash/version materially changed | ACCEPTED (implemented) | lane's `resumeFailsClosedWhenInterventionDefinitionChanges` + `InterventionVersionMismatchException` → named HTTP 409 `intervention_version_mismatch` (handler added this session); IT drives resume with a wrong hash → fails closed, correct identity → ACTIVE |
| 5 | Attempt evidence attachable by reference, no duplication | ACCEPTED (implemented) | `attachEvidence(runId, "attempt:<id>", role)` stores the reference only — the canonical attempt/evidence rows are untouched; IT attaches a REAL human-marked attempt id and asserts the run's evidence list holds the reference |
| 6 | Run completion cannot directly mutate mastery | ACCEPTED (implemented + DB-level proof) | the service has no learner-model dependency (lane's design, javadoc-pinned); IT asserts skill-state row bit-identical (attempts, updatedAt, mastery) across create→activate→steps→evidence→complete; the cold-start flow asserts `countByLearnerId == 0` after a full run lifecycle |
| 7 | Governed learner-model path remains the sole state-mutation authority | ACCEPTED (implemented) | the only learner-state writes in the run path are NONE (above); the attempt's own evidence flows through the existing governed path (BKT/BDT via `EvidencePublisher`, unchanged and separately verified) |
| 8 | Completed run reconstructable from persisted records/evidence refs | ACCEPTED (implemented) | `GET /api/v1/learners/me/intervention-runs/{id}` returns identity + snapshot refs + ordered steps + evidence references; IT asserts the full reconstruction after completion |
| 9 | Terminal history append-only at domain level | ACCEPTED (implemented) | aggregate: terminal runs reject activate/pause/resume/recordStep (`requireNotTerminal`); this session closed the remaining gap — `attachEvidence` on a terminal run now fails closed (unit test + IT), because a late attachment would rewrite a terminal run's evidence set (contract §5) |
| 10 | Tests cover version mismatch and mutation boundary | ACCEPTED (implemented) | unit: `resumeFailsClosed…`, `resumeSucceeds…`, `evidenceCannotBeAttachedToTerminalRun`; scenario: 4 composition tests; IT: both flows above; full suite 540 green |

## Hard boundaries (contract §3) — honored

- No generic workflow engine — the only states are the contract's §5 machine; no step scheduler, no dynamic tools.
- No autonomous LLM-defined tools/steps — the surface executes no tools at all; `allowedToolIds` is descriptive of the intervention's bounded scope.
- No mastery arithmetic inside the run — proven at DB level (criterion 6).
- No second learner model — zero learner-model code touched.
- No curriculum/KG mutation — run rows reference nodes/subjects only.
- No generated-question dependency — the scenario attaches existing validated-question attempt evidence.

## Live-found defects (both fixed honestly, zero gates weakened)

1. **PRODUCT DEFECT — jsonb columns rejected every write on real Postgres**
   (`d75a0e3` live probe: create-from-recommendation → 500). The four jsonb
   columns carried only `columnDefinition="jsonb"` with plain String fields;
   Hibernate sent varchar parameters and PostgreSQL rejected the INSERT. The
   concurrent lane's persistence tests were mock-based and the Testcontainers
   IT had never executed, so the defect was invisible until the first
   production write. Fix `d330a3a`: `@JdbcTypeCode(SqlTypes.JSON)` on all four
   fields (the `DocumentChunk.element_ids` house pattern), verified against a
   REAL user-space Postgres (embedded pgserver loaded with the actual Flyway
   V1..V26 migrations): create → activate → step → evidence → complete →
   read-back → resume gate all green.
2. **PRODUCT DEFECT (minor, boundary fidelity) — the named resume-mismatch 409
   was swallowed** (`d75a0e3` live probe G9: wrong identity returned generic
   `conflict` instead of `intervention_version_mismatch`): the controller's
   state-conflict wrapper caught the mismatch subclass and rethrew
   `ConflictException` before the named handler could fire. Fix `02643ed`: the
   subclass passes through unchanged; the IT now pins the named mismatch
   explicitly.

## Honest remainders

- `InterventionRunFlowIT` has now EXECUTED once in real Docker CI (runs
  `35075687695`/`35077858230`/`35078454055`, 2026-09-16): both flows are RED
  on fixture defects (above), so the IT is NOT claimed as passing. Until the
  two fixtures are corrected, core-ci remains RED and — per the CI-recovery
  runbook's own rule ("real-failure = stop, no r3") — this blocks the
  pilot-readiness pipeline (r3/t0/T-032). Fixing the two fixtures (assert the
  deterministic NBA action the policy actually produces, and root the fixture
  paper on a curriculum-ingested subject with a KG root) is the smallest
  safe, contract-defined engineering task on core.
- The prototype stays **PROPOSED → (pending) ACCEPTED**: the contract §14 gate
  requires the acceptance criteria above PLUS an understanding of
  persistence/query cost before promotion. §14 audit (2026-09-17
  reconciliation): criterion claims 1–10 are demonstrated (unit 540 green in
  CI + live 10/10 on production), but **no artifact yet documents the
  persistence/query-cost analysis** (row growth, query patterns,
  normalization trigger from contract §10), and the IT fixtures above are
  red — therefore **E2 is NOT ready for ACCEPTED promotion**; promotion
  remains the operator's decision and is NOT claimed.
- Live verification is read-mostly: run creation/lifecycle is exercised on production with a fresh test learner (append-only rows, zero learner-state impact by the proven boundary).
