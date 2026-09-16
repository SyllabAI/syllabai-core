# Evidence State Transition Hardening

**Status:** VERIFIED for the covered lifecycle invariants; CONCURRENCY: **REAL DEFECT FOUND AND FIXED** (2026-09-17, executed against real Postgres — see § "Executed concurrency result"). Sequential lifecycle invariants: VERIFIED. Publisher idempotence: VERIFIED. Multi-part settlement/evidence semantics: VERIFIED locally and in CI. Cross-transaction concurrency: **VERIFIED after fix** (duplicate-evidence defect reproduced 6/6, fixed, regression test green).

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
