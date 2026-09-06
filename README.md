# syllabai-core

SyllabAI backend — the **Java modular monolith**. This is where the course project's object-oriented design lives.

> Part of the SyllabAI project · master pack: [`SyllabAI/syllabai`](https://github.com/SyllabAI/syllabai) · read `MASTER_SPEC.md` there before writing code.

## Stack (locked, ADR-001/009)

- **Java 25** (LTS) · **Maven**
- **Spring Boot 4.1.x** (Boot 3.x reached EOL 2026-06-30 — do not use)
- **Spring AI 2.0.x** — chat/embedding abstraction, pgvector support, OpenAI-compatible endpoints (Groq)
- Spring Security (JWT + RBAC), JPA/Hibernate + native SQL, Flyway migrations
- **Neon PostgreSQL + pgvector** (single data substrate: relational + KG graph tables + vectors)
- Free-LLM chain behind `LlmProvider`: Groq `llama-3.3-70b-versatile` → Gemini 2.5 Flash → OpenRouter
- In-process PDF extraction: `opendataloader-pdf` (Apache-2.0, Maven Central) — lands in `syllabai-parser` / Wave 1
- Object storage behind `ObjectStorage`: local dev filesystem / **Cloudflare R2** (S3 SDK)
- Deploy: Docker on **Render free tier** (no state on local filesystem; cold starts accepted)

## Modules (packages, not microservices — ADR-012)

```text
com.syllabai
├── identity          (auth, users, RBAC, consent)
├── curriculum        (boards, subjects, versions)
├── knowledge         (KG nodes/edges, misconceptions, traversal)
├── content           (canonical document store, chunking, embeddings, vector retrieval)
├── assessment        (question bank, attempts, evidence contract)
├── smartmark         (AI marking, deterministic validators, κ gate)
├── learner           (BKT, BDT, Ebbinghaus decay, learner state)
├── tutor             (KA-RAG: hybrid retrieval, fusion, grounded generation, citations)
├── diagnostic        (struggle inference — Wave 4, placeholder)
├── recommendation    (next-best-step — Wave 3, placeholder)
├── teacher           (class views — Wave 4, placeholder)
├── research          (learning-log telemetry, model/prompt/experiment registries)
└── infrastructure    (LLM/vector/graph/storage adapters, jobs)
```

Each module owns its application services, domain objects, ports, and persistence adapters. Cross-module communication via domain events and contracts — never direct repository access.

## Implemented so far (Wave 0–2 + science core + KA-RAG foundation)

| Task | Status | What |
|------|--------|------|
| T-001 | ✅ | Spring Boot 4.1.1 / Java 25 / Spring AI 2.0.1 skeleton, 13-module package map |
| T-002 | ✅ | Flyway V1–V7 (identity, KG, assessment, learner, research + Edexcel IAL Chemistry seed), docker-compose Postgres 17 + pgvector |
| T-003 | ✅ | Spring Security JWT (register/login/me), RBAC roles, CORS |
| T-004 | ✅ | `/api/v1` DTO boundaries, springdoc OpenAPI, global error handling |
| T-006 | ✅ | GitHub Actions CI (build + test, JDK 25) |
| T-007 | ✅ | `ObjectStorage` port + local + R2 (S3 SDK) adapters |
| T-012 | ✅ | `KnowledgeGraphRepository` with recursive-CTE prerequisite closure + tree + misconception traversal |
| T-014 | ✅ | `AssessmentEvidenceRecordedEvent` evidence contract with expressed + observed misconception sets (Observer: learner model + telemetry react) |
| T-015 | ✅ | BKT engine (L₀=0.1, slip=0.1, guess=0.25, T=0.1 — config + model registry versioned) |
| T-016 | ✅ (v0) | Learner state aggregate: skill_states + misconception_states + read model with decay-adjusted mastery |
| T-017 | ✅ (v0) | BDT misconception engine: tagged distractors strengthen, correct answers weaken monitored misconceptions (prior 0.3) |
| T-018 | ✅ | Ebbinghaus decay service (τ=30/90/365 by band, floor, review threshold) + nightly `@Scheduled` job |
| T-020 | ✅ (v0) | Append-only telemetry event store (JSONB): all six Cycle-1 event types emitted (ATTEMPT_SUBMITTED, BKT_UPDATED, BDT_UPDATED, REVIEW_SCHEDULED, DECAY_APPLIED, SELF_DOUBT_FLAGGED) |
| T-023 | ✅ (core) | `LlmProvider` port + Spring AI adapters + `FailoverLlmChain` (Groq→Gemini→OpenRouter, health/cooldown, admin health endpoint, **experiment pinning** via config + experiments registry — unpinned experiments fail loudly, pinned never fail over) |
| T-008–T-011, T-019, T-021, T-022 | ✅ | Wave-1/2 content+assessment fabric: V8 multi-part model (ExamPaper/QuestionVersion/QuestionPart/MarkScheme/MarkPoint/Answer/SmartMarkResult/HumanMark + κ evaluations), timed/untimed fluency gap, Smart Mark Strategy pipeline (candidate → bounds/coverage/mark-sum validators → append-only results; blank answers deterministic; LLM never final truth), κ ≥ 0.60 release gate, T-011 parser draft bridge + teacher validation workflow + ingestion anchors, ServableQuestionSpec (unvalidated content never serves) |
| T-013 | ✅ | V11 content module: canonical document store (verbatim JSONB, checksum-idempotent), deterministic chunking (element-ordered, block-bound, provenance element_ids), `EmbeddingProvider` port + Gemini text-embedding-004 (768-dim, no failover by design), pgvector `vector(768)` + HNSW cosine search, teacher content APIs (ingest/embed/search), model-registry seed `content-embedding` (§19) |
| T-010 | ✅ | Curriculum ingestion (V12-adjacent, no schema change): parser `curriculum-draft.json` (schema 1.1, per-node §17 provenance: section id, element ids, page, confidence) → `CurriculumIngestionService` (idempotent by provenance fingerprint, namespaced KG codes, all-SUGGESTED) + `CurriculumReviewService` node/version validation gate → teacher curriculum API; real Edexcel IAL Chemistry 2018 spec ingested: 6 units / 20 topics / 15 subtopics |
| T-024 | ✅ (v0) | KA-RAG foundation (tutor package): deterministic intent (`GraphKnowledgeRetriever`, VALIDATED nodes only), `ContentVectorRetriever` adapter (cosine floor), `ReciprocalRankFusion` (rank-only, k=60), `NoReranker` Strategy, `LearnerContextAssembler` (mastery/misconceptions/fluency-gap briefs), `GroundedTutorGenerator` (prompt `tutor-grounded/v1`, temp 0.2, free-LLM chain), `SimpleCitationResolver` (deep links), `KaRagService` orchestration + grounding gate (empty evidence → deterministic refusal, no LLM call), `POST /api/v1/tutor/ask`, V12 (KA_RAG_COMPLETED telemetry + §19 registries) |
| T-C02 | ✅ (main) | GLM-OCR bridge (V13, merged PR #3 → main `7c6f122`): verified parser outputs for one QP/MS pair → existing T-013 canonical store + existing T-011 assessment ingestion (all SUGGESTED), parser contract persisted verbatim in `glm_ocr_bridge_records` (drafts + reconciliation + review findings — October Q18 and 1A 80-vs-120 conflicts stay review-visible), rerun-safe by canonical pair identity, embedding never implicit; controlled entries: `POST /api/v1/teacher/content/glm-ocr/pairs` + ops CLI `--syllabai.glmocr.pair-dir`; verified on the six real GLM-markdown-sample files (3 WPH11 pairs: 20/20/19 questions) — `docs/t-c02-bridge.md` |
| T-C03 | 🔄 (branch) | ONE controlled real-corpus batch (not a firehose): batch root of pair bundles → bounded `GlmOcrBatchService` (firehose guard default 10; ONE transaction — all pairs land or none) → DB-verified audit report (`all-content-suggested`, `no-implicit-embedding`, `not-learner-servable`, `conflict-preservation`, `deterministic-rerun` + row-count evidence + in-run idempotency pass) written to `batch-audit-report.json` for human review; ops CLI `--syllabai.glmocr.batch-dir` fails non-zero on any invariant failure; parser side: `GlmOcrPairCli` (syllabai-parser) is the committed markdown→bundle production command; verified on the 3 real WPH11 pairs — `docs/t-c03-batch.md` |

## Quickstart (local)

```bash
# 1. database
docker compose up -d            # Postgres 17 + pgvector on :5432

# 2. config
export SYLLABAI_JWT_SECRET="$(openssl rand -base64 48)"
# optional LLM keys (app boots fine without them):
export SYLLABAI_GROQ_API_KEY=... SYLLABAI_GEMINI_API_KEY=...
# optional embeddings for T-013 retrieval (free tier, no CC):
export SYLLABAI_EMBEDDING_GEMINI_API_KEY=...

# 3. run (local profile = demo users + dev jwt secret)
mvn spring-boot:run -Dspring-boot.run.profiles=local
# → demo users: teacher@syllabai.dev / teacher-demo-1234, student@syllabai.dev / student-demo-1234
```

### API tour

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"student@syllabai.dev","password":"student-demo-1234"}' | jq -r .accessToken)

# curriculum + knowledge graph
curl -s localhost:8080/api/v1/curriculum/subjects | jq
curl -s -H "Authorization: Bearer $TOKEN" \
  localhost:8080/api/v1/knowledge/nodes/20000000-0000-0000-0000-000000000001/tree?includeMisconceptions=true | jq

# quiz flow: list questions → submit attempt → learner state
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/questions | jq
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  localhost:8080/api/v1/attempts \
  -d '{"questionId":"40000000-0000-0000-0000-000000000001","chosenOptionId":"41000000-0000-0000-0000-000000000001","responseTimeMs":42000,"confidence":2,"selfDoubtFlag":true,"timedCondition":false}' | jq
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/learners/me/state | jq

# T-013 content pipeline: ingest a parser canonical document (schema 1.0) as a mark
# scheme, embed its chunks, cosine-search the index (all teacher-gated)
TEACHER=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"teacher@syllabai.dev","password":"teacher-demo-1234"}' | jq -r .accessToken)
curl -s -X POST "localhost:8080/api/v1/teacher/content/documents?kind=MARK_SCHEME" \
  -H "Authorization: Bearer $TEACHER" -H 'Content-Type: application/json' \
  --data-binary @canonical-ms.json | jq
curl -s -X POST localhost:8080/api/v1/teacher/content/documents/<id>/embed \
  -H "Authorization: Bearer $TEACHER" | jq
curl -s "localhost:8080/api/v1/teacher/content/documents/search?query=chlorine%20iodine&kind=MARK_SCHEME" \
  -H "Authorization: Bearer $TEACHER" | jq

# T-010 curriculum ingestion: POST the parser's curriculum-draft.json
# (real Edexcel IAL Chemistry 2018 spec: 6 units / 20 topics / 15 subtopics)
curl -s -X POST "localhost:8080/api/v1/teacher/curriculum/drafts" \
  -H "Authorization: Bearer $TEACHER" -H 'Content-Type: application/json' \
  --data-binary @curriculum-draft.json | jq
curl -s "localhost:8080/api/v1/teacher/curriculum/versions" \
  -H "Authorization: Bearer $TEACHER" | jq
curl -s -X POST "localhost:8080/api/v1/teacher/curriculum/nodes/<nodeId>/validate" \
  -H "Authorization: Bearer $TEACHER" | jq

# T-024 KA-RAG: ask the tutor (backend surface; chat UI is T-025)
curl -s -X POST "localhost:8080/api/v1/tutor/ask" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"bonding and structure of molecules"}' | jq
# → answer with [n] citations, matched spec topics, evidence count, refusal flag

# T-C02 GLM-OCR bridge: ingest ONE verified parser QP/MS pair (five JSON outputs
# exactly as the parser workbench wrote them); re-running the same pair is safe.
# Embedding is NOT part of this operation — embed later via the T-013 endpoint above.
curl -s -X POST "localhost:8080/api/v1/teacher/content/glm-ocr/pairs" \
  -H "Authorization: Bearer $TEACHER" -H 'Content-Type: application/json' \
  -d '{"qpCanonical": <qp-canonical.json>, "msCanonical": <ms-canonical.json>,
        "qpDraft": <qp-draft.json>, "msDraft": <ms-draft.json>,
        "reconciliation": <reconciliation.json>}' | jq
# → qpDocument/msDocument INGESTED|DUPLICATE, examPaper, question/part/point
#   counts, reconciliation status, reviewFindings, embeddingSkipped: true
curl -s "localhost:8080/api/v1/teacher/content/glm-ocr/papers/<paperId>/findings" \
  -H "Authorization: Bearer $TEACHER" | jq

# T-C03 controlled batch (ops CLI, NOT learner-facing): a batch root of pair
# bundles (one sub-directory per pair, the five parser outputs each). Bounded
# (default 10, refuse beyond), ONE transaction, DB-verified safety invariants,
# audit artifact written to <batch-root>/batch-audit-report.json for human
# review. Fails non-zero if ANY invariant fails. Safe to re-run: nothing changes.
# Generate bundles from real GLM-OCR markdown with syllabai-parser's GlmOcrPairCli.
java -jar syllabai-core.jar --syllabai.glmocr.batch-dir=/path/to/batch-root \
  [--syllabai.glmocr.batch-max-pairs=10]
# ops CLI equivalent (no HTTP needed):
#   java -jar syllabai-core.jar --syllabai.glmocr.pair-dir=/path/to/pair-dir

# OpenAPI
open http://localhost:8080/api/v1/docs
```

Submitting the *wrong* option on question 1 picks the distractor tagged with the
moles/grams misconception — watch `misconceptionStates.probability` jump from the
0.3 prior in `/learners/me/state`, and BKT mastery drop on the topic. Submitting Q2
*correctly* then **weakens** that same misconception (Q2's distractor C monitors it):
0.3 → 0.75 (wrong) → 0.5 (correct), with every step logged as BDT_UPDATED telemetry
(`evidence: TAGGED_DISTRACTOR` / `CORRECT_ANSWER`).

## Tests

```bash
mvn test    # 196 unit tests: BKT math, BDT Bayes incl. correct-answer weakening,
            # evidence assembly, telemetry coverage, decay formula/bands/floor,
            # decay-job events, chain failover, experiment pinning, storage,
            # Smart Mark pipeline + κ gate, multi-part ingestion bridge,
            # canonical validation, deterministic chunking, embedding idempotency,
            # curriculum ingestion + review gate, RRF fusion, deterministic intent,
            # learner context assembly, citations, grounded generation, KA-RAG orchestration,
            # GLM-OCR bridge mapper/service/CLI/controller against the real 3-pair fixtures,
            # T-C03 batch orchestration: bounded discovery, two passes, invariants, audit

mvn verify   # + Testcontainers ITs (CI, Docker): full marking loop
             # (MultipartMarkingFlowIT), content pipeline ingest→chunk→embed→
             # cosine search (ContentPipelineIT), real-spec curriculum ingestion +
             # validation gate (CurriculumIngestionIT), KA-RAG end-to-end with
             # real-corpus fixtures + stub generator (KaRagFlowIT), GLM-OCR bridge
             # end-to-end incl. rerun idempotency + conflict visibility (GlmOcrBridgeIT),
             # T-C03 batch end-to-end: 3 real pairs, invariants, second-run zero-new-rows,
             # bound refusal, whole-batch rollback on a late-failing pair, learner-serving
             # boundary through the real QuestionController, audit artifact (GlmOcrBatchIT)
```

## OOP expectations (graded course project)

Strategy (retrieval, interventions, assessment), Factory/Provider (LLM/embedding/parser adapters), Observer (telemetry events), Adapter (external engines), Repository, Specification. Meaningful responsibilities only — no pattern theater. See Master Spec §23.
