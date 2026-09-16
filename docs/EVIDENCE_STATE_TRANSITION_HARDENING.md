# Evidence State Transition Hardening

**Status:** VERIFIED for the covered lifecycle invariants; CONCURRENCY: **REAL DEFECT FOUND, FIXED, CI-GREEN, AND DEPLOYED — VERIFIED + FIXED** (2026-09-17 closure record below; see § "Executed concurrency result" and § "Closure record"). Sequential lifecycle invariants: VERIFIED. Publisher idempotence: VERIFIED. Multi-part settlement/evidence semantics: VERIFIED locally and in CI. Cross-transaction concurrency: **VERIFIED after fix** (duplicate-evidence defect reproduced 6/6, fixed, regression test green; core CI fully green at the fixed tree, fix deployed through the normal production gate and verified live read-only). Remaining mixed-path/distributed concurrency scope: **UNVERIFIED** (unchanged — see the final section).

## Scope

This record is a focused hardening audit of the structured-assessment evidence lifecycle. It does not change the r3 measurement protocol, learner-model semantics, or canonical curriculum semantics.

## Current invariants verified by repository tests

1. **Structured evidence waits for settlement.** A multi-part attempt does not emit assessment evidence while any answer remains `PENDING`.
2. **Evidence carries the settled whole-attempt total.** The completing mark emits the final aggregate marks, not the first-part partial total.
3. **Evidence is single-fire after authoritative marking.** A later human override changes the research/grade state but does not re-run evidence.
4. **Smart Mark remains gated.** Before κ release, Smart Mark is provisional and emits no assessment evidence. After κ release, an accepted run may emit evidence, subject to the same settled-attempt guard.
5. **Publisher-level idempotence is fail-closed.** `EvidencePublisher.publishGraded` emits once and returns false thereafter; duplicate MCQ publication throws without emitting a second event.
6. **Conservative correctness is preserved.** For structured evidence, only full marks are treated as `correct=true`; partial credit remains represented by the marks payload.
7. **Learner-state mutation remains downstream of evidence.** Assessment marking does not directly mutate canonical learner state; the evidence event is the hand-off.

Primary coverage lives in:

- `src/test/java/com/syllabai/assessment/EvidencePublisherTest.java`
- `src/test/java/com/syllabai/teacher/TeacherMarkingServiceTest.java`
- `src/test/java/com/syllabai/smartmark/SmartMarkServiceTest.java`
- `src/test/java/com/syllabai/it/MultipartMarkingFlowIT.java`

## Concurrency regression harness

`src/test/java/com/syllabai/it/EvidenceStateConcurrencyIT.java` has now been added under the `agent-chatgpt:` lane. It uses real Postgres/Testcontainers and separate executor threads to exercise:

- same-answer concurrent authoritative marking;
- different-part concurrent authoritative marking;
- final aggregate marks after concurrent part completion;
- post-race retry/override idempotence;
- learner-state observation count as the persisted proxy for evidence-event multiplicity.

**Status (superseded 2026-09-17):** executed. The quota recovered 2026-09-16 and CI Docker runs became available; the harness ran for the first time in CI run 35136630860 (commit 78c8afc) — see the executed-result section below. This paragraph is preserved for the record: the repository environment available to that lane had no Docker runtime, and hosted minutes were then exhausted, so the harness had not been executed at authoring time and was correctly recorded UNVERIFIED.

The harness is intentionally test-only. No production code, migration, learner-state semantics, r3 measurement rule, or T0 measurement procedure was changed in this lane.

## Remaining production-hardening gap (resolved 2026-09-17 — see the executed-result section)

`Attempt.markEvidenceEmitted()` is an in-memory boolean transition. The service layer is transactional, but `Attempt` has no database-level compare-and-set/optimistic-version guard specifically protecting the evidence emission transition.

That created two distinct concurrency questions that were **not** established by the current unit tests or by an executed integration test at the time this section was written:

- Two transactions loading separate copies of the same attempt could potentially both observe `evidenceEmitted=false` and race to publish evidence. — **CONFIRMED as a real defect** (duplicate evidence + double learner-state projection, 6/6 reproduction rounds) and **FIXED** (attempt-row pessimistic lock; details below).
- Two transactions marking different parts of the same multi-part attempt could each observe the other part as still `PENDING`, producing a liveness case where neither transaction emits the now-settled attempt's evidence. — **NOT reproduced** (8/8 rounds correct); analysis shows this case is prevented structurally by the attempt-row UPDATE that both transactions flush before their settlement read (row lock serializes the second transaction's read behind the first commit). The fix makes this guarantee explicit rather than incidental.

These were design-level risks when written; the first is now a demonstrated-and-fixed production defect, the second a verified-safe path with the mechanism understood.

## Proposed smallest safe follow-up

Execute `EvidenceStateConcurrencyIT` once a real Postgres/Testcontainers runner is available. The required invariant is:

> For one structured attempt, exactly one `AssessmentEvidenceRecordedEvent` may be committed after the attempt becomes fully settled, regardless of concurrent authoritative marking order.

The test should establish at minimum:

- same-answer concurrent marking;
- different-part concurrent marking;
- out-of-order part completion;
- retry after a transaction conflict;
- event count = exactly one;
- event marks = final settled aggregate;
- learner-state projection changes exactly once.

Only after the failing/successful behavior is demonstrated should the implementation choice be selected (for example optimistic locking, an explicit atomic DB transition, or another transaction-safe idempotency mechanism). Avoid adding a migration solely on the basis of this inference.

## Non-goals

- No change to frozen `EVIDENCE-CYCLE-R3-SPEC-2026-09-16.md` measurement rules.
- No change to BKT/BDT parameters.
- No change to mastery semantics.
- No change to Smart Mark κ thresholds.
- No production data mutation.
- No hosted CI dispatch while the Actions quota remains exhausted.

## Evidence boundary

Current repository tests establish the lifecycle behavior for sequential service calls and publisher idempotence. The new concurrency harness exists but remains **UNVERIFIED** until executed against real Postgres. That distinction is intentional and must remain explicit until a real database-backed run proves or falsifies the concurrency safety invariant.

---

## Executed concurrency result — REAL DEFECT FOUND AND FIXED (2026-09-17, `agent-zai:` lane)

### Environment and method

- **Database:** real Postgres 17 (`pgvector/pgvector:pg17`) via the repo's standard Testcontainers setup, executed in GitHub Actions core-ci (`mvn verify`, JDK 25, ubuntu-latest). The sandbox authoring environment has no Docker; CI runs are the execution vehicle.
- **Workers:** 2 concurrent threads per race, released simultaneously by a ready/start latch pair; each worker executes one REAL `@Transactional` service call (`TeacherMarkingService.recordHumanMark`) on its own thread — no mocks, no simplified concurrency model.
- **Overlap verification:** every race records both workers' nanoTime intervals and asserts they intersect — a race whose transactions did not overlap fails the test as "proves nothing".
- **Evidence counting:** committed `ATTEMPT_SUBMITTED` telemetry rows per attemptId (the persisted form of `AssessmentEvidenceRecordedEvent`) plus `BKT_UPDATED` rows and `skill_states` counters — asserted from the database after the races complete, never from return values.
- **Isolation level:** Postgres default READ COMMITTED (no override anywhere in the app).

### Scenarios and observed outcomes

| Scenario | Test | Rounds | Result BEFORE fix (run 35136630860 @ 78c8afc) | Result AFTER fix (run 35137802476 @ 6c4ac03) |
|---|---|---|---|---|
| A — same-answer concurrent marks | `sameAnswerConcurrentMarksEmitEvidenceExactlyOnce` | 6 | **FAIL 6/6 — duplicate evidence** | **PASS 6/6** |
| B — different-part concurrent marks | `differentPartConcurrentMarksEmitOneFinalAggregate` | 8 | PASS 8/8 | PASS 8/8 |
| C — out-of-order completion (b settles before a) | `outOfOrderCompletionSettlesOnFinalAggregateNotArrivalOrder` | 1 | PASS | PASS |
| D — duplicate/retry before and after settlement | `retryAndDuplicateSubmissionNeverDoublesEvidenceOrProjection` | 1 | PASS | PASS |

**Observed database state, scenario A before the fix (every round):** `evidence=2, bkt=2, skillAttempts=2, failures=0` — both transactions committed, each fired its own evidence event for the SAME attempt, the learner state counted one attempt twice (attempts=2, correctCount=2), and NO exception was raised anywhere: the failure mode is exactly the "system looks healthy while producing subtly wrong learner-state evidence" class. `skill_states` has `@Version` + `uq_skill_state`, but the guard is ineffective in this interleaving because the loser's write lands strictly after the winner's commit (no in-flight overlap to conflict on).

**Scenario B analysis (why it passed):** both marking transactions dirty the attempts row (`humanMarked`) BEFORE their settlement read; Hibernate's AUTO flush therefore takes the attempt row lock ahead of the read, so the second transaction's settlement read runs after the first commit and sees both parts marked — it fires the single evidence with the full aggregate. Safe, but by incidental flush ordering; the fix converts this into an explicit guarantee.

**Scenario C:** after the first (partial) mark — zero evidence events, zero BKT rows, no `skill_states` row, `evidence_emitted=false`, partial total 1; after the completing mark — exactly one evidence event, aggregate 2, correctness=true, payload references (attemptId/questionId/topicNodeIds/marks) all correct, BKT posterior exactly 5/14 for one correct observation from l0=0.1 (deterministic, matches the frozen r3 protocol P.4-a parameters).

**Scenario D:** duplicate partial marks before settlement fire nothing and never double the partial total; post-settlement retries (override path) add no evidence event, no BKT row, no counter increment, and never overwrite the final aggregate.

**Conflicts/retries observed:** zero transaction failures in every round, before and after the fix (post-fix scenario A resolves as winner-marks + loser-override, both committing cleanly).

### Root cause

`Attempt.markEvidenceEmitted()` is an in-memory check-then-set. Two transactions that both load the attempt before either commits each hold `evidenceEmitted=false` in their own persistence context; each then observes the settled answer state (the row lock on the marked answer serializes them) and each publishes its own `AssessmentEvidenceRecordedEvent`. There is no optimistic-version column on `attempts` and no DB constraint on the evidence transition to arbitrate.

### Why sequential tests missed it

Every pre-existing test (unit `TeacherMarkingServiceTest`, `SmartMarkServiceTest`, `EvidencePublisherTest`; IT `MultipartMarkingFlowIT`) drives the marking service ONE call at a time. The defect requires two concurrent transactions whose entity loads both precede the first commit — a condition no sequential test can produce and no mock-based test can reproduce (it needs real row locking, real persistence contexts, and real commit visibility).

### Smallest correct fix (implemented, commit 6c4ac03)

Every authoritative marking transaction now takes `SELECT … FOR UPDATE` on the attempt row BEFORE loading any attempt state:

1. `AnswerRepository.findAttemptIdById` — id-only FK projection (no entity load, no proxy init).
2. `AttemptRepository.findByIdForUpdate` — `@Lock(PESSIMISTIC_WRITE)`.
3. `TeacherMarkingService.recordHumanMark` and `SmartMarkService.markAnswer` (human and κ-gated smart marking obey the same settlement invariant) resolve the attempt id, take the lock, THEN load answer+attempt.

The race loser blocks at the lock and re-reads the winner's committed state: evidence already fired → override path (never re-fires); still settling → its completing-mark check sees the other parts' committed marks. Sequential behavior is unchanged (one short per-attempt row lock; no cross-attempt contention; attempt is always the first lock a marking transaction takes, so no lock-order inversion). No schema change, no migration, no workflow change, no change to BKT/decay/evidence semantics, no change to the frozen r3/t0 protocols.

### Regression coverage

`EvidenceStateConcurrencyIT.sameAnswerConcurrentMarksEmitEvidenceExactlyOnce` failed 6/6 before the fix and passes 6/6 after it; scenarios B/C/D pin the unaffected behaviors. Full suite at the fix commit: **581 unit tests green (1 known skip) + 20/21 IT classes green** in CI run 35137802476 — the only RED is `RevisionNoteFlowIT` (a different lane's brand-new test, erroring identically before and after this change on an unrelated entity-mapping bug: `revision_note_asset` maps the `bytes`/`size_bytes` columns crossed, `bytea` column receiving a bigint; owned by the revision-notes lane, not touched here).

**Closure (2026-09-17):** the revision-notes lane landed its repair (`8b5643a` — `@JdbcTypeCode(SqlTypes.BINARY)` bytea binding replacing the Postgres-OID `@Lob` mapping; `7eb621a` — corrected IT assertions), and core CI went **fully green**: run `35139255878` at `7eb621a` (push) and the re-run `35145761997` at the then-current main `0dcd0a4` (operator-directed `workflow_dispatch`; code-identical to `7eb621a` on all of `src/` — the only delta is this repo's docs). Artifact-verified totals in both runs: surefire **581 tests / 0 failures / 0 errors / 1 skipped** and failsafe **74 ITs / 0 / 0 / 0**, with `EvidenceStateConcurrencyIT` 4/4, `MultipartMarkingFlowIT` 2/2, and `RevisionNoteFlowIT` 2/2 all GREEN — 21/21 IT classes. (Counting note: earlier session records said "582 unit"; the artifact-level truth is 581 — `LiveProviderBenchmark` is container-level `@EnabledIfEnvironmentVariable`-gated and emits no XML when disabled, so it never appeared in either green run's reports.)

### Commit chain

- `78c8afc` — `agent-zai: strengthen evidence concurrency harness` (scenarios A–D, DB-level assertions; supersedes the 2-scenario version)
- `6c4ac03` — `agent-zai: fix concurrent evidence settlement race — attempt-row lock before state load`
- Related, same session: the harness's first CI execution was unblocked by the revision-notes `ObjectMapper` wiring repair (lane commit `6a5f7b7`; the injection broke every full-context startup — all 21 IT classes — and would have broken any prod deploy).

### Remaining limitations (explicitly UNVERIFIED)

- Concurrency above 2 workers per attempt is not tested (the lock argument extends mechanically, but not empirically).
- κ-released Smart Mark racing a human mark on the same attempt is covered by the same lock in code but not by a dedicated mixed-race test.
- Multi-instance/distributed deployment behavior, database failover, and network-level retry semantics: UNVERIFIED (single-instance assumptions).
- Postgres isolation levels other than READ COMMITTED: UNVERIFIED.
- The harness measures the marking path only; MCQ submit-path concurrency (single-transaction insert+publish) is out of scope here.

---

## Closure record — VERIFIED + FIXED + DEPLOYED (2026-09-17, session 85, `agent-zai:` lane)

Operator directive: verify `6c4ac03` is reachable from `origin/main`; inspect the exact `FOR UPDATE` diff; confirm both marking paths acquire the lock before any state/evidence decision; wait for the revision-notes lane's `RevisionNoteAsset` repair; re-run core CI; on fully green mark the defect VERIFIED + FIXED; deploy through the normal production gate; read-only-verify the deployed artifact contains the fix; keep the remaining mixed-path/distributed cases explicitly UNVERIFIED.

### 1. Reachability and diff inspection (git, executed)

- `git merge-base --is-ancestor 6c4ac03 origin/main` → **REACHABLE** (verified at `origin/main` = `0dcd0a4`).
- Full diff of `6c4ac03` re-inspected: `AnswerRepository.findAttemptIdById` (id-only FK-column projection, no entity/proxy load), `AttemptRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`), and the two marking services taking that lock at transaction entry. No file touched by any later commit on main (`git log 6c4ac03..HEAD -- <the four files>` is empty; the only post-fix changes on main are docs, the revision-notes entity/IT repair, and docs).

### 2. Lock-before-state audit at current main (code, executed)

- `TeacherMarkingService.recordHumanMark`: `findAttemptIdById` → `findByIdForUpdate` → only then `findWithPartAndAttempt` (state load), `evidenceEmitted()` decision, and any evidence publication. The `revising` branch and `publishGraded` call all sit strictly after the lock.
- `SmartMarkService.markAnswer`: identical order — lock at entry, then answer/attempt/question-version load, κ gate, completing-mark settlement check, and `publishGraded`.
- The batch endpoint `POST /api/v1/teacher/marking/smart-mark-batch` routes every item through `SmartMarkService.markAnswer` (one transaction per item); its pre-read is a non-transactional skip-optimization and makes no state/evidence decision.
- The only other evidence emitter is `AssessmentService.submit` (MCQ): the attempt row is created and the evidence fired within the SAME transaction on a row no concurrent marker can yet see — structurally outside the marking race (and outside this harness's scope, as recorded above).
- `findAttemptIdById`/`findByIdForUpdate` have no other production callers (repo-wide audit).

### 3. RevisionNoteAsset repair waited for and confirmed

The owning lane's repair landed on main: `8b5643a` (bytea binding via `SqlTypes.BINARY`; the live-verified failure was `@Lob byte[]` mapping to OID/bigint on Postgres) and `7eb621a` (IT assertions corrected to check the asset on the note that carries the diagram, corpus-count invariance instead of emptiness). Both green in CI runs `35139255878` and `35145761997` (`RevisionNoteFlowIT` 2/2).

### 4. Core CI re-run: FULLY GREEN

- `35139255878` (push, `7eb621a`): success — the first fully-green run of the fixed tree.
- `35145761997` (`workflow_dispatch` at `0dcd0a4`, the operator-directed re-run, 2026-09-16T20:18Z): **success** — every job step green; artifact-verified surefire 581/0/0/1 and failsafe 74/0/0/0 (details in the regression-coverage closure above). `0dcd0a4` is code-identical to `7eb621a` on all of `src/`.
- Verdict per the directive: **this concurrency defect is VERIFIED + FIXED.**

### 5. Deployment through the normal production gate (verified, not re-triggered)

The normal production gate for this service is Render autoDeploy from `main` (service `srv-dagijie7bikc73bc0460`). The fix chain reached production through exactly that gate: with the operator-supplied Render credential, the session-84 verification (executed 2026-09-16 ~20:05 UTC, after the fix landed) recorded the LIVE deploy as exactly `7eb621a` (deploy `dep-dalekmjn`, status live, prior deploys deactivated) — and `6c4ac03` is an ancestor of `7eb621a` (git-verified), so the deployed artifact contains the fix. The only push to main since (`0dcd0a4`, docs-only) does not change the artifact: the Dockerfile build stage copies `pom.xml` and `src/` only — `docs/` never enters the image — so any autoDeploy it triggered serves a byte-identical runtime. No manual deploy action was needed, and none was taken.

### 6. Read-only production verification that the deployed artifact contains the fix (executed 2026-09-16 ~20:26 UTC)

- **Liveness:** `GET /actuator/health` → `200` `{"status":"UP"}` (warm, Render-served, `rndr-id` response header present).
- **Fail-closed surface:** unauthenticated GETs on the marking surface (`/api/v1/teacher/attempts`), the intervention surface, and a nonsense control path all → `401` (uniform pre-auth security; no state echo).
- **Deployed-artifact fingerprint:** `GET /api/v1/api-docs` (publicly readable OpenAPI) lists **96 paths, exactly equal to the 96-path route set derived from the current fix-containing source tree — zero divergence in either direction** — including every route that landed inside the fix window before the fix commit (`/api/v1/learners/me/revision-notes*` and `/api/v1/admin/revision-notes/*` from `c7578a2` 18:13Z, `/api/v1/admin/llm/chain-health` from `b8ce0f4`) and the marking routes that sit on the fixed services. The deployed backend is therefore ≥ the 18:13Z fix-window state, not the pre-deploy `02643ed` tree.
- **Exact-SHA leg (executed evidence, session 84):** the Render-API verification above pinned the live deploy to exactly `7eb621a`; combined with git ancestry and autoDeploy-from-main-only mechanics (no rollback path exercised; the only credential able to redeploy an older tree is the operator's, the same one that verified `7eb621a` live), the chain closes: **the deployed artifact contains the fix.**
- **Honest residual:** a fresh direct deployed-SHA re-read via the Render API is credential-gated in this session (the operator-supplied key is deliberately not persisted in the authoring sandbox), and route-set equality alone cannot distinguish a hypothetical same-routes pre-fix deploy — that gap is closed by the session-84 exact-SHA record and the deploy-gate mechanics, not by the HTTP probes alone. Recorded as such, not papered over.

### 7. Explicitly UNVERIFIED (unchanged, per directive)

The "Remaining limitations (explicitly UNVERIFIED)" list above stands verbatim: >2 workers per attempt, a dedicated mixed smart+human race test, multi-instance/distributed deployment, database failover, network-level retry semantics, and non-READ-COMMITTED isolation levels. Nothing in this closure weakens or reclassifies any of them.
