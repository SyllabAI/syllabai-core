# T-C03 — one controlled real-corpus batch + bridge audit

**Status:** implemented on branch `codex/t-c03-batch` (PR #5, issue #4); merge is a
human decision. Builds on T-C02 (merged, main `7c6f122`); the T-C02 bridge itself is
**unchanged** — this task adds bounded batch orchestration and a durable audit
around it, plus the parser-side production command that turns real GLM-OCR
Markdown pairs into bridge bundles.

**Scope discipline (deliberate):** ONE bounded batch of real GLM-OCR QP/MS
pairs — not a corpus-wide firehose. The 2,724-file corpus is untouched; the
40 audited batches stay parked until this batch's report is human-reviewed.

## The chain T-C03 proves end-to-end

```text
GLM-OCR Markdown (real corpus files)
   └─ GlmOcrPairCli (syllabai-parser, committed production command)
        qp-canonical.json + ms-canonical.json     ← GLM-OCR → canonical documents
        qp-draft.json + ms-draft.json
        reconciliation.json                        ← conflict evidence, never merged
   └─ GlmOcrBatchService (syllabai-core, NEW)
        per pair: GlmOcrIngestionService.ingestPair  (the UNCHANGED T-C02 bridge)
            canonical → ContentIngestionService   (T-013: validate → dedup → chunks)
            drafts    → PastPaperIngestionService (T-011: paper/questions/schemes/points)
            record    → glm_ocr_bridge_records     (V13: verbatim contract)
        batch: bound check → ONE transaction → in-run idempotency pass
               → DB-verified invariants → batch-audit-report.json  ← bridge audit
```

## Batch semantics

- **Batch root** = a directory of pair sub-directories, each holding the five
  parser outputs (`qp-canonical.json`, `ms-canonical.json`, `qp-draft.json`,
  `ms-draft.json`, `reconciliation.json`). A sub-directory with *some* of the
  five files is a fail-loud `ConflictException` (a half-written bundle is never
  silently skipped); a directory with none (e.g. a README folder) is skipped.
- **Bounded:** more pairs than `maxPairs` (default **10**, configurable) is
  refused *before any row is written*. The bound is the firehose guard; the
  operator splits the corpus into reviewed batches instead of raising it.
- **Atomic:** the whole batch is ONE transaction — a failing pair rolls back
  every pair in the batch. No partial batches exist.
- **Deterministic order:** pairs are processed sorted by directory name.

## The audit report (the deliverable)

`GlmOcrBatchAuditReport` (JSON, written to `<batch-root>/batch-audit-report.json`
by the CLI; the IT writes it to `target/t-c03-batch-audit/` and CI uploads it
as a workflow artifact) contains:

1. **Per pair, both passes** — first-pass outcome (document/paper status,
   question/part/scheme/point counts, chunk counts, reconciliation status +
   conflict evidence, findings count) and the idempotency-pass outcome
   (must be `DUPLICATE` with identical counts).
2. **Row-count evidence** — DB row counts for every kind (documents, chunks,
   papers, versions, schemes, points, bridge records) at three stages: before,
   after first pass, after the idempotency pass. The last two must be equal.
3. **Five verified invariants** — queried against the database, not asserted
   from service return values:

   | Invariant | What is actually checked |
   |-----------|--------------------------|
   | `all-content-suggested` | every imported ExamPaper, QuestionVersion and MarkScheme row is `SUGGESTED` (mark points follow their scheme) |
   | `no-implicit-embedding` | every chunk of the batch's documents has `embedded_at` NULL — embedding stays an explicit T-013 operation |
   | `not-learner-servable` | zero `VALIDATED` question versions among imported papers — the servable gate (VALIDATED required) blocks all of them |
   | `conflict-preservation` | every paper has its bridge record; stored reconciliation status + findings count match the pair results; `REVIEW_REQUIRED` (1A 80-vs-120) survives verbatim |
   | `deterministic-rerun` | idempotency pass: every paper `DUPLICATE`; row counts unchanged (cross-transaction rerun is additionally proven by `GlmOcrBatchIT` running the whole batch twice) |

4. **`allInvariantsPassed`** — the CLI's exit gate: any failure ⇒ non-zero
   exit (an unsafe batch must never look successful). The artifact is still
   written first, so the failure is reviewable.

## Controlled entries

- **Ops CLI (batch):** `java -jar syllabai-core.jar --syllabai.glmocr.batch-dir=<root>
  [--syllabai.glmocr.batch-max-pairs=10]` — activates only when the property is
  set; normal boots are unaffected.
- **Ops CLI (single pair, T-C02, unchanged):** `--syllabai.glmocr.pair-dir=<dir>`.
- **No new HTTP endpoint.** The existing teacher-gated
  `GET /api/v1/teacher/content/glm-ocr/papers/{paperId}/findings` is the
  per-paper review surface; the batch report is the batch-level review surface.
- **Parser side:** `syllabai-parser` `GlmOcrPairCli`
  (`syllabai-glmocr-pair <qp.md> <ms.md> <out-dir> [--uri-prefix <prefix>]`)
  is the committed production command for Markdown → five-file bundle
  (deterministic ids; honest `extractedAt`; conflict-preserving reconciliation).
  It replaces the session-10 uncommitted workbench script that generated the
  committed fixtures.

## What T-C03 does NOT do

- No new migration (V13 carries all durable evidence; the audit is an artifact).
- No embedding call, no learner-facing serving, no validation-state changes —
  the batch lands everything `SUGGESTED` and stops.
- No corpus-wide run: the real batch is the three WPH11 pairs (the entire
  current real GLM-OCR corpus); wider runs wait for human review of this
  batch's report.
- No changes to the T-C02 bridge contract, the six fixture expectations, the
  parser contract or PR #1.

## Verification

- **Unit (196/196):** `GlmOcrBatchServiceTest` (discovery/skip/fail-loud
  incomplete bundles, bound refusal before any bridge call, two-pass structure
  against the real fixture bundles, invariant assembly, JSON round-trip, a
  leaked-VALIDATED version failing the report) + `GlmOcrBatchCliTest`
  (artifact written into the batch root, non-zero exit on invariant failure).
- **IT `GlmOcrBatchIT` (CI, real pgvector Postgres):** the 3 real pairs —
  first run all `INGESTED` with the T-C02-verified counts (June 20/20/20/51 OK;
  October 20/26/20/53 + Q18 finding; 1A 19/25/19/69 REVIEW_REQUIRED 80-vs-120),
  all five invariants green, exact row-count deltas (6 documents / 3 papers /
  3 bridge records / 59 versions); full second batch run in a NEW transaction —
  everything `DUPLICATE`, zero new rows; bound refusal writes nothing; audit
  artifact round-trips.
- **Two regression proofs in the same IT:** (1) *whole-batch atomicity* — a
  fourth pair whose canonical documents ingest FRESH (re-identified bundle) and
  then fails LATE at T-011's duplicate-paper refusal must roll back the ENTIRE
  batch: the three fully-ingested pairs AND the poisoned pair's own partial
  writes (its two canonical documents + chunks) — the database returns to the
  pristine V7 seed state; (2) *the real learner-serving boundary* — through the
  actual `QuestionController`: the learner selection contains none of the 59
  imported question ids while the 8 seed MCQs still serve (the gate blocks
  unvalidated imports, not the endpoint), the topic-scoped query on an imported
  paper's anchor topic returns nothing, and direct fetch of an imported question
  refuses with 404 while direct fetch of an authoritative question serves.
- **Parser (71/71):** `GlmOcrPairCliTest` — five-file bundle, deterministic
  ids on rerun, drafts claim their canonical ids, June clean 80/80, 1A
  80-vs-120 written out.
