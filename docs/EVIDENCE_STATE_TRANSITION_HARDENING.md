# Evidence State Transition Hardening

**Status:** VERIFIED for the covered lifecycle invariants; CONCURRENCY GAP = UNVERIFIED / PROPOSED follow-up

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

## Remaining production-hardening gap

`Attempt.markEvidenceEmitted()` is an in-memory boolean transition. The service layer is transactional, but `Attempt` has no database-level compare-and-set/optimistic-version guard specifically protecting the evidence emission transition.

That creates two distinct concurrency questions that are **not** established by the current unit tests:

- Two transactions loading separate copies of the same attempt could potentially both observe `evidenceEmitted=false` and race to publish evidence.
- Two transactions marking different parts of the same multi-part attempt could each observe the other part as still `PENDING`, producing a liveness case where neither transaction emits the now-settled attempt's evidence.

These are design-level risks, not verified production defects. Do not claim them as observed incidents without a reproducible database-backed test.

## Proposed smallest safe follow-up

Before introducing schema or service changes, add a database-backed concurrency test that establishes the required invariant:

> For one structured attempt, exactly one `AssessmentEvidenceRecordedEvent` may be committed after the attempt becomes fully settled, regardless of concurrent authoritative marking order.

The test should cover at minimum:

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
- No hosted CI dispatch is required for this documentation-only hardening record.

## Evidence boundary

Current repository tests establish the lifecycle behavior for sequential service calls and publisher idempotence. They do **not** establish cross-transaction concurrency safety. That distinction is intentional and must remain explicit until a real Postgres/Testcontainers concurrency test proves otherwise.
