# T-C02 — GLM-OCR Bridge: Implementation Record

Status: **implemented on `codex/t-c02-glmocr-bridge`** (issue `SyllabAI/syllabai-core#2`).
Contract: `docs/t-c02-bridge-contract.md` (invariants unchanged, all honored).
Source of truth for the parser contract: `syllabai-parser` main @ `9eb35ab`
(GLM-OCR Markdown adapter + QP/MS extraction + reconciliation, 68/68 tests +
12/12 cross-language conformance).

## What this is (and is not)

A controlled content-ops bridge from the VERIFIED parser outputs into the two
EXISTING ingestion paths — **no new ingestion architecture**:

```text
parser outputs for one QP/MS pair
  ├── qp-canonical.json  ──┐
  ├── ms-canonical.json  ──┴─→ ContentIngestionService (T-013)      [existing, untouched]
  │                           validate → checksum dedup → JSONB → deterministic chunks
  │                           (NEVER embeds — embedding stays a separate explicit operation)
  ├── qp-draft.json      ──┐
  ├── ms-draft.json      ──┴─→ GlmOcrDraftMapper → PastPaperIngestionService (T-011)  [existing, untouched]
  │                           ExamPaper → Question → QuestionVersion → QuestionPart
  │                           → MarkScheme → MarkPoint, ingestion-anchor topic,
  │                           everything SUGGESTED until teacher validation
  └── reconciliation.json ──→ preserved verbatim + assembled review findings
                              → GlmOcrBridgeRecord (V13) — nothing discarded
```

## The five new pieces

| Piece | File(s) | Role |
|---|---|---|
| DTO mirrors | `teacher/ingestion/GlmOcrPaperDraftDto`, `GlmOcrMarkSchemeDraftDto`, `GlmOcrReconciliationDto` | parser JSON schemas mirrored field-for-field (Master Spec §27: shared schema, never a Java dependency) |
| Mapper | `teacher/ingestion/GlmOcrDraftMapper` | the ONLY translation point GLM-OCR → T-011 draft + review-finding assembly; deterministic, side-effect-free |
| Orchestrator | `teacher/ingestion/GlmOcrIngestionService` | one pair, one transaction: T-013 → T-011 → bridge record; fail-loud bundle validation; rerun-safe |
| Persistence | `V13__glm_ocr_bridge_records.sql`, `GlmOcrBridgeRecord` + repository | the smallest justified structure for everything the assessment model cannot represent |
| Controlled entries | `teacher/GlmOcrIngestionController` (`POST /api/v1/teacher/content/glm-ocr/pairs`, `GET …/papers/{id}/findings`), `teacher/ingestion/GlmOcrIngestionCli` (`--syllabai.glmocr.pair-dir=<dir>`) | one-pair-at-a-time operations surface; teacher-gated HTTP; conditional ops CLI |

`MarkSchemeRepository` gained one additive query (`findByPaperId`) for bridge
reporting. Nothing else in existing code changed.

## Parser → core field map (the "important discovery" audit)

| Parser field | Core destination | Note |
|---|---|---|
| paper.board/qualification | `ExamPaper.board/qualification` | from the MS draft (identity-rich side of the real corpus) |
| paper.paperReference | `ExamPaper.paperCode` | June 2025 QP/MS pair has none → null, never derived from publication codes |
| paper.session | `ExamPaper.sessionLabel` | |
| paper.subject | **not inferred** | T-011 resolves an honest generic subject; teachers remap in review |
| paper.logNumber / publicationCode / examDate / duration | bridge record `ms_draft` JSONB (verbatim) | visible to review, never guessed into relational columns |
| canonicalDocumentId (QP/MS) | `ExamPaper.questionPaperDocumentId / markSchemeDocumentId`, `QuestionVersion.sourceDocumentId`, `MarkScheme.sourceDocumentId` | links assessment rows to the T-013 store |
| question.questionId / number / stem / marks / confidence | `Question.externalRef`, question number, `QuestionVersion.stem/marks/extractionConfidence` | |
| part.label / text / marks / confidence | `QuestionPart.label/prompt/marks` | roman subparts keep "b-i" convention |
| MS entry label → mark-point ref | `MarkPoint.ref` | "13(a)"→"13-a", "13(b)(i)"→"13-b-i" (mechanical re-format of the same printed structure; unmatched refs stay question-level) |
| MS mark points (split on "(N)") | one `MarkPoint` each | unknown marks (null) → **0** (unknown), never a guess |
| MS entries without "(N)" markers | **one whole-entry `MarkPoint`** | MCQ rationale rows + unsplit cells — otherwise 13 of 20 questions would silently lose their marking evidence |
| MS entry.marks (rowspan-deferred=null) | preserved in `ms_draft` JSONB | never guessed |
| mark point dependentOn/ecf/alternatives/anyTwoFrom/reject/rawText | `ms_draft` JSONB (verbatim) + `acceptance_criteria` stays **empty until teacher-authored** | the pipeline never invents Smart Mark criteria (§7) |
| guidance lines, IC table, QWC flags | `qp_draft`/`ms_draft` JSONB (verbatim) | |
| MCQ options + correctOption | `qp_draft`/`ms_draft` JSONB (verbatim) | T-011 ingests STRUCTURED; options remain review-visible but not yet `QuestionOption` rows (deliberate — no parallel path, no invented MCQ serving) |
| question.marksKnown=false | `qp_draft` JSONB + review findings (warnings) | e.g. October Q18 |
| questionTotals / sectionTotals / paperTotal | drafts JSONB + reconciliation | conflicts stay review findings |
| figure refs (expired signed URLs) | canonical document (T-013) + drafts JSONB | `availability=unavailable-signed-url`; never fetched, never faked |
| reconciliation findings + warnings | bridge record `review_findings` JSONB + `reconciliation` JSONB | evidence-first: never merged, never repaired |
| extractionMethod (qp/ms) | `ExamPaper.extractionMethod="glm-ocr-qp-v1+glm-ocr-ms-v1"` + per-draft in JSONB | both parser identities preserved |

**Nothing is discarded.** Anything the assessment model cannot represent lives
verbatim in the bridge record JSONB (the smallest justified persistence
structure, decision documented here).

## Determinism & idempotency

- Canonical `documentId` = the parser's deterministic identity
  (SHA-256 of checksum+engine+version). The bridge never generates one.
- Canonical rows: idempotent by source checksum (existing T-013 behavior).
- Assessment rows: the bridge record is keyed `UNIQUE (qp_document_id,
  ms_document_id)`; a rerun resolves to it and reports DUPLICATE without
  calling T-011 again. Relational row ids remain database-generated, but the
  same imported source resolves to the same rows.
- Whole-pair atomicity: one transaction — a failure (e.g. T-011's
  `ConflictException` for a cross-path collision) leaves nothing behind.
- Fail-loud bundle checks: the QP/MS drafts must reference the supplied
  canonical document ids, and the reconciliation's paper totals must match the
  drafts — a mixed bundle is rejected, never guessed into place.

## Embedding

The bridge stops after chunking. `DocumentEmbeddingService` is not a dependency
of any bridge class; the IT proves all chunks stay pending (`embeddedAt is
null`) after ingestion. Embedding remains the existing explicit teacher
operation (`POST /api/v1/teacher/content/documents/{id}/embed`).

## Servability

Everything lands SUGGESTED (T-011 semantics, unchanged): paper, versions,
schemes. The IT verifies through the real serving projection
(`QuestionController` + `ServableQuestionSpec`) that imported questions never
appear in learner selection and direct fetches 404.

## Verification

Real fixtures: the six files of `SyllabAI/Past-Papers/GLM-markdown-sample`
(three WPH11 pairs), run through the production parser classes at `9eb35ab`
(unmodified) into `src/test/resources/fixtures/glm-ocr/` (generator:
`scripts/GenerateBridgeFixtures.java` in the session workspace; outputs
committed — canonical JSON, QP/MS drafts, reconciliation, summary).

| Fixture | Questions | Parts | Schemes | Mark points | Reconciliation |
|---|---:|---:|---:|---:|---|
| June 2025 WPH11/01 | 20 | 20 | 20 | 51 | OK — paper totals 80/80 aligned; warnings relayed |
| October 2025 WPH11/01 | 20 | 26 | 20 | 53 | OK; **Q18 warning review-visible** (part-sum 2 vs printed 8) |
| October 2025 WPH11/01A | 19 | 25 | 19 | 69 | **REVIEW_REQUIRED — QP 80 vs MS 120 preserved, never merged** |

Tests: `GlmOcrDraftMapperTest` (16), `GlmOcrIngestionServiceTest` (5),
`GlmOcrIngestionCliTest` (2), `GlmOcrIngestionControllerTest` (3) — unit, real
fixtures; `GlmOcrBridgeIT` (6) — Testcontainers, covers canonical persistence
+ provenance, assessment bridge + SUGGESTED, rerun idempotency, both audited
conflicts, servability exclusion, no-implicit-embedding.

## Out of scope (unchanged)

No corpus-wide ingestion (T-C03 stays open: ONE controlled batch + human review
before any wider run). No curriculum/prerequisite inference. No image
fabrication. No embedding changes. PR #1 (T-026/T-027) untouched — human merge
decision.
