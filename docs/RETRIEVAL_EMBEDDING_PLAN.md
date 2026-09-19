> **In-repo copy (2026-09-20, R2):** imported verbatim from the retrieval-lane handoff brief
> `syllabai-tutor-retrieval-plan.md` v1.0 (parser-lane, 2026-09-20) so supersede pointers in
> archived docs resolve inside this repository. Substrate (P1) shipped at commit `81144e8`
> (V33 + atom-boundary chunking + headers + embed_rev); where reality already diverged
> (T-C07 curriculum scoping live, V29 Kind values, model pre-fixed at f134c234) the deltas
> are logged in the shared worklog, Task 25/26.

# SyllabAI Tutor — Retrieval, Chunking & Embedding Plan (v1.0)

**Prepared for:** the retrieval/embedding agent (handoff brief)
**Prepared by:** parser-lane agent (pdflane), 2026-09-20
**Status:** this document is the authoritative starting point for retrieval work. Where it conflicts with older docs in the repos, **this document wins** — then update the older docs to say so (see §1).
**Scope:** what we chunk, how we chunk it, what we embed, how retrieval routes per intent, and what must be true before the v2 corpus goes live. Chemistry (4CH1/4CH0) is the first subject; everything here is subject-generic by construction.

---

## 0. TL;DR — the ten decisions

1. **One knowledge spine, three retrieval layers, three query intents.** The spec (syllabus statements) is the spine everything hangs from. Notes + textbook = **knowledge layer** (Explain). Parsed QP + MS = **evidence layer** (verbatim, page-anchored). Structured question bank = **bank layer** (Fetch + Enumerate). Match the medium to the intent; never make one corpus serve all three.
2. **Chunk QP/MS per question (atom), not per token window.** The atoms schema (§8 of `QPMS_ATOM_SCHEMA.md`) already mandates this; core's `ChunkingService` does not implement it yet. That is the single biggest chunking gap.
3. **Do NOT embed full question+MS text as the enumerate corpus.** Enumerate/Fetch go SQL-first over the structured bank; embed only tiny **question cards** (≤60 tokens of header + tags + one-line summary) as the semantic fallback. Full Q+MS text is assembled on demand from atoms.
4. **Retrieval headers mirrored into real metadata columns** — so SQL predicates never parse header strings. The bridge already bakes section-level headers (v1.1.1, commit `eef89fb`); what remains is core-side **per-chunk** header projection + the metadata columns, and this must be in place **before** the first production re-embed.
5. **Subject scoping is a SQL predicate on every query, not a prompt hint.** `subject_id` (and course spec-range/unit for T-C07 scoping) is mandatory; this closes the known subject-leak issue.
6. **One-shot Embedding v2 migration:** headers + metadata columns + `gemini-embedding-001` (replacing `text-embedding-004`) + new Kinds (NOTE, TEXTBOOK) in a single re-embed under `embed_rev=2`. Never migrate twice. The currently-built 11-paper canonical bridge output **waits** until v2 is ready — do not ingest it as-is.
7. **Supersede, don't coexist:** the legacy 91-paper corpus (rev1) is retired after rev2 passes the eval gate; otherwise RRF double-ranks the same papers.
8. **KaRAG stays the Explain router — do not build LLM-extracted entity GraphRAG.** `KaRagService` already implements intent → KG + vector → RRF → grounded generation → citations → deterministic refusal. What it needs is spec-code linkage on chunks + subject scoping, not a knowledge-graph side project.
9. **Coverage is a metric, not an aspiration.** "The tutor can answer ALL questions on a subject" is verified by a spec-coverage report: every spec point needs ≥1 note chunk, ≥1 exam question, ≥1 MS point. Gaps become content-ops tickets.
10. **No retrieval change ships without the eval harness** (golden query set + gates). The harness runs before cut-over and after every corpus change.

---

## 1. Mandatory first step: docs archaeology + codebase audit

Before writing any ingestion code, you must (a) inventory every retrieval/RAG/embedding-related doc in the repos, and (b) audit the live code so the plan below is reconciled with reality. Older docs **will** contain outdated ideas (fixed-window chunking, "embed everything", no subject filter, `text-embedding-004`, global cosine with no scoping). Your job is to mark them superseded, not to silently follow them.

**Doc inventory (classify each as CURRENT / OUTDATED / SUPERSEDED, then add a supersede banner + pointer to this doc):**

- `syllabai-core`: `docs/t-c02-bridge*.md`, `docs/t-c03-batch.md`, `docs/CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md`, `docs/LEARNER_INTERACTION_MEMORY_IMPLEMENTATION.md`, ADR-009 (embedding provider), Master Spec §13 (tutor), anything describing chunking/retrieval/KaRAG.
- `syllabai-parser`: any retrieval/index notes; `QPMS_ATOM_SCHEMA.md` §8 (retrieval contract — CURRENT, it is quoted extensively below).
- `syllabai-central` / `syllabai-web`: any chatbot/tutor retrieval docs.

**Code audit checklist (verify these claims; report deltas, do not code yet):**

| # | Claim to verify | Where |
|---|---|---|
| A1 | `ChunkingService` packs canonical textBlocks at target 300 / max 800 tokens, block-boundary-respecting, **not** atom-boundary-aware, no headers | `content/ChunkingService.java` |
| A2 | `ContentRetrievalService.search(query, kind, limit)` filters by `Document.Kind` only; **no subject filter anywhere in the vector path** | `content/ContentRetrievalService.java`, `content/ChunkVectorRepository.java` |
| A3 | `Document.Kind` = {QUESTION_PAPER, MARK_SCHEME, SYLLABUS, OTHER} — no NOTE/TEXTBOOK | `content/Document.java` |
| A4 | `KaRagService`: deterministic intent → KG topics + vector → RRF → rerank (NoReranker v0) → grounded generation → citations → deterministic refusal on empty evidence | `tutor/KaRagService.java` |
| A5 | Knowledge graph spine exists: `knowledge` package (KnowledgeNode/Edge, RelationType), seeded from `resources/concept-graph/concept_edges.yaml` (SPEC/NOTE/TOPIC nodes) | `knowledge/*` |
| A6 | Structured bank exists: `assessment` package (Question, QuestionVersion, QuestionPart, QuestionOption, MarkScheme, MarkPoint, QuestionTopic, ExamPaper) | `assessment/*` |
| A7 | Embedding provider is Gemini with key `SYLLABAI_EMBEDDING_GEMINI_API_KEY`; confirm the model id actually configured (queue says `text-embedding-004` → must become `gemini-embedding-001`) | `content/GeminiEmbeddingProvider.java`, `EmbeddingProperties.java`, Render env |
| A8 | Production corpus state: 172 docs = 91 chem papers (QP+MS), 2,333 chunks @768d; notes/subject resources not embedded; 1,439 questions + 4,294 mark points structured-only | DB queries, ingestion campaign reports |
| A9 | Data defects: session labels non-canonical ("Summer" vs "June"), ~10 papers missing paper_code, ~10 MS missing | `exam_papers` / ingestion audit |
| A10 | Canonical bridge exists and is green: `syllabai-parser` branch `tc17-work`, commit `2959ec9`, `tools/pdflane/atoms_to_canonical.py`, 23 tests, produces QP+MS canonical schema-1.0 docs per paper, leak-guarded | parser repo |

**Deliverable of this step:** an inventory table (doc → verdict → action) + an audit note appended to the shared worklog. If any claim above is wrong in a way that changes the plan, STOP and flag before building.

---

## 2. Critique of the owner's ideas (point by point)

Verdicts: ✅ adopt as-is · 🟡 adopt with correction · 🔴 adopt the goal, reject the mechanism.

### 2.1 🟡 "The tutor of a subject/unit must answer ALL questions related to that subject/topic"

Right requirement, wrong verb. You cannot ship "all" unless "all" is measurable, and today nothing measures it. Two corrections:

- **Make coverage a first-class metric.** Build a **spec-coverage report**: for each spec point of the subject, does it have ≥1 note chunk, ≥1 exam question (from the bank), ≥1 MS point? Anything failing is a content gap that becomes a ticket — not a silent retrieval miss. The tutor's ability to answer "everything on electrolysis" is then a property you can assert per release, because the question "what does the corpus cover for spec 1.44?" is a SQL join, not a vibe.
- **Enforce scope mechanically.** A subject/unit tutor that "can answer everything" also must answer *only* from its subject. That is a SQL predicate (`subject_id` + course spec-range/unit filter, T-C07), enforced in the repository layer, verified by a leakage test (chemistry corpus, physics query → no physics chunks survive). Prompt-level scoping ("you are the chemistry tutor") is a UX nicety, never the control.

### 2.2 ✅🟡 Chunk/embed SME revision notes + parsed specification (notes mapped to spec)

Adopt — with two corrections that make it much better:

- **Chunk notes at the spec boundary the SME mapping already gives you.** The notes are mapped to spec points; that mapping *is* the chunk boundary. One chunk = one note section covering one spec point (or a tight group of sibling points, e.g. 1.22–1.25). Never split a single point's explanation across chunks; split a section only if it exceeds the size cap, and then at sub-headings. This yields semantically pure chunks (each one is literally "the note for spec X"), which is what makes per-spec-point ranking and coverage checks trivial.
- **Treat the spec as the spine, not as prose to win retrieval contests.** Spec statements are short; embedded alone they make weak vectors that lose cosine fights against rich textbook prose. Their real jobs are: (a) the **linkage key** every other artifact points to (`spec_codes` on chunks, rows in the KG/spec table); (b) coverage accounting; (c) a *thin* vector presence (statement + section path + unit in the header) so queries like "what does the spec require for 1.22?" resolve. Store spec in SQL (`spec_points`-style rows, or the existing KG SPEC nodes — audit will tell which) **and** a small set of enriched chunks. Do not over-invest in making spec chunks rank highly; that's the notes' job.

### 2.3 🟡 Parse textbooks → map to spec points → chunk/embed

Adopt as the **third tier of the knowledge layer**, with caution:

- **Do not block on precise spec alignment.** Paragraph-to-spec-point mapping is many-to-many, noisy, and expensive to verify. Do **section-level coarse mapping** (chapter/section → one or more spec codes, confidence-scored, SME spot-review for the top sections only) and record `confidence` in metadata. A wrong coarse mapping costs almost nothing at query time (it only scopes candidate retrieval); a months-long fine-alignment project costs a lot.
- **Chunk by heading hierarchy, not by tokens.** Keep the full heading path (`Ch 7.2 / Electrolysis of aqueous salts`) in the header; split at 400–800 tokens on paragraph boundaries when a section is long.
- **Rank textbooks below notes.** Both cover the same spec points; notes are curated for IGCSE, textbooks are depth and alternative explanations. Per-kind fusion weights (§7) handle this — no separate index needed.
- **Phase-gate it.** Textbook ingestion is P3, not P1. Ship spec + notes + papers first; the tutor is dramatically better with those three alone, and the eval harness will tell you what the textbook adds.
- **Copyright note (owner decision):** published textbooks are internally licensed material; keep them behind auth and never expose verbatim long excerpts in chat answers beyond fair instructional quoting.

### 2.4 ✅ Chunk/embed low-level parsed QP and MS

Yes — "if you know what I mean" understood: this is the **evidence layer** built from the atoms/1.1 products via the canonical bridge (`atoms_to_canonical.py`, commit `2959ec9`, 23 tests green, 11-paper dry run: 22 docs, 0 page regressions, deterministic). Adopt with four corrections:

- **Chunk per question (atom), not per token window.** This is already the mandated contract — atoms schema §8: *"Atom-level chunking (primary): one chunk per atom, rendered from stem + parts + MS points; chunk identity = `<paper dir>#q<number>`… Page-aligned chunking (secondary)"*. Core's `ChunkingService` currently token-packs canonical blocks and can cross a question boundary mid-sentence — that must be fixed (§4.1). A question split across two chunks is a citation integrity bug, not a nuance.
- **QP and MS stay separate documents.** The bridge already enforces this (QP docs cannot carry answers). Keep it: it preserves the CLA leak policy (answers gated on attempt), keeps citations clean ("QP p.5" vs "MS p.3"), and lets the tutor fetch the MS deliberately instead of leaking it incidentally.
- **Ingest with per-chunk headers + metadata columns, or don't ingest yet.** The bridge already bakes section-level retrieval headers (v1.1.1: `[International GCSE Chemistry 4CH1 | June 2024 | Paper 1C | Question 7]` on the first text-bearing element of each question section), but chunks formed from later blocks inherit no header, and the metadata columns don't exist. Since re-embedding everything is inevitable for the model fix, **pause the 11-paper ingest until Embedding v2 is ready** — ingest once, not twice (§6).
- **MS chunking unit = all points of one atom together**, including `guidance`, `allow/reject/ignore`, and levels bands. Never one chunk per mark point — MS points are 1-line fragments that are meaningless alone and poisonous to the index.

### 2.5 🟡 High-level "question + its mark scheme" units tagged with year/topic/subtopic/spec points (for "all photoelectricity questions 2019–2024")

The artifact is right; the medium is wrong. Two corrections:

- **Enumerate/Fetch are SQL problems first.** "2019 to 2024" is a `WHERE year BETWEEN 2019 AND 2024`. Embeddings cannot enumerate exhaustively and cannot filter dates reliably. The structured bank already exists (1,439 questions, 4,294 mark points, `question_topics`) — the work is backfill/tag quality/query APIs, **not** embedding full question text. Do this in the order: backfill from atoms (authoritative, marks-verified) → normalize tags → deterministic Enumerate API → only then consider semantic help.
- **The only vector component in this layer is the question card.** A card is ≤60 tokens: retrieval header + one-line content summary + tags (`topics`, `spec_codes`, `year`, `series`, `paper_code`, `qnum`). Cards give the semantic fallback for fuzzy enumeration ("questions about strong vs weak acids 2019–2024" where the topic name doesn't match the taxonomy). Full QP text + MS points are assembled on demand from the bank/atoms by ID. This avoids embedding the same question text three times (paper chunk + merged unit + card) — the vector corpus stays clean and dedup-by-(paper,qnum) stays trivial.
- **Topic taxonomy authority (open decision §13.6):** `question_topics` currently carries ad-hoc tags. Decide the authority: Edexcel topic codes + a controlled keyword list, with free-text tags allowed but excluded from Enumerate matching until normalized. Tag backfill from atoms should prefer spec-code joins over string topic names.

### 2.6 🟡 Use KaRAG "whenever it is best"

KaRAG is not an idea here — it's shipped code (`KaRagService`, T-024, Master Spec §13): deterministic intent → KG topics + vector candidates → RRF fusion → rerank (v0 NoReranker) → grounded generation with citations, **deterministic refusal with zero LLM call** when no evidence survives. Adopt it as the **Explain router**, with three corrections:

- **Do not build LLM-extracted entity GraphRAG.** The knowledge graph you need already exists in better form: the spec spine + SME mappings + concept edges (153 edges VALIDATED). It's deterministic, auditable, and free. Generic GraphRAG would add cost and nondeterminism to a domain where the "graph" is literally the syllabus.
- **KaRAG's KG leg needs the new layers wired in.** Today KG topics aren't linked to chunk content by spec code. Once chunks carry `spec_codes` and KG nodes carry spec/topic codes, the KG leg becomes a *scoping + expansion* mechanism (match topic → pull sibling/related spec codes → filter/boost vector candidates). That is the actual upgrade path for KaRAG, and it's a join, not a graph project.
- **KaRAG is for Explain only.** Fetch and Enumerate must bypass the generator entirely (deterministic assembly). Never let an LLM paraphrase what a JOIN can return exactly.

### 2.7 What your list was missing (gaps found in audit)

1. **Subject filter** — known leak: retrieval is global cosine at `kind=null`, threshold 0.15. Multi-subject launch makes this P0.
2. **`Document.Kind` has no home for notes/textbook** — they'd land as OTHER. Add `NOTE`, `TEXTBOOK` kinds (A3).
3. **Legacy/new corpus coexistence** — 91 legacy papers + new bridge papers = same content twice in RRF. Need supersession, not dedup-by-checksum (different checksum spaces).
4. **Embedding model migration is queued but unscheduled** — fold it into v2 (§6) so it happens exactly once.
5. **No eval harness** — nothing measures whether retrieval got better. Golden set + gates (§9).
6. **Data hygiene** — session label normalization, 10 missing paper_codes, 10 missing MS (§8).
7. **Citation contract** — every Explain answer must cite spec codes + paper/Q/page; the atoms products carry all of it (`pages`, page markers); the answer layer must be held to it.
8. **Figures** — image-only questions (assets are cropped PNGs with alt text) are not answerable from text chunks alone. Phase-2 decision (§13.4), but the metadata must keep `assets` links so the upgrade is possible.

---

## 3. Target architecture

```text
                        ┌────────────────────────────────────────────────┐
                        │                SPEC SPINE (SQL)                │
                        │  spec points (code, statement, unit, subject)  │
                        │  + KG nodes/edges (existing knowledge package) │
                        └──────┬───────────────┬───────────────┬─────────┘
                    spec_codes │               │ spec_codes    │ spec_codes
                 ┌─────────────▼───┐   ┌───────▼─────────┐  ┌──▼──────────────────┐
  KNOWLEDGE LAYER│ spec chunks     │   │ EVIDENCE LAYER  │  │ BANK LAYER (SQL)    │
  (vector)       │ note chunks     │   │ (vector)        │  │ questions/parts/    │
                 │ textbook chunks │   │ QP chunks (1/atom)  │ mark_points/topics  │
                 │                 │   │ MS chunks (1/atom)  │ + question CARDS    │
                 └─────────────┬───┘   └───────┬─────────┘  └──┬──────────────────┘
                               │   RRF + per-kind weights      │
                        ┌──────▼───────────────────────────────▼──────┐
                        │              INTENT ROUTER                  │
                        │  EXPLAIN → KaRAG (KG + vector hybrid)       │
                        │  FETCH   → deterministic SQL + assembly     │
                        │  ENUMERATE→ SQL filters (+ card fallback)   │
                        │  all: subject_id predicate, MANDATORY       │
                        └─────────────────────────────────────────────┘
```

Rules of the architecture:

- Every chunk in every layer carries `subject_id` + `spec_codes` where applicable + a retrieval header. The spine connects layers: spec point 1.22 links to its note chunk, its textbook sections, every exam question tagged with it, and the KG topic nodes.
- Layers are ranked by per-kind fusion weights per intent (§7), not by separate indexes.
- Everything in the bank layer is deterministic; embeddings only assist fuzzy entry points into it.

---

## 4. The chunking matrix (what we chunk and how)

### 4.1 The one chunker change that matters first

`ChunkingService` (A1) packs blocks at 300/800 tokens and will split an atom across chunks. Fix it as follows, before any v2 ingest:

1. Canonical docs from the bridge carry a **group key per textBlock** (atom identity: `q3`, `q4`… — the bridge knows the atom boundaries; expose them as a per-block `groupKey`/`sectionId` in the canonical JSON; additive, schema-1.0 tolerant).
2. `ChunkingService` treats a group-key change as a hard boundary (flush current chunk), exactly like the existing oversized-block path. Token packing continues *within* a group: an atom larger than `maxTokens` splits at block boundaries inside the atom (stem long data responses), never across atoms.
3. Chunk drafts gain `groupKey` → becomes `atom_number`/`qnum` metadata (nullable for non-paper kinds).
4. **Per-chunk header projection:** the chunker stamps *every* chunk with the header line, built deterministically from document identity + group key + the chunk's page range (§4.2 grammar) — this subsumes the bridge's first-element header (v1.1.1), which survives as harmless duplicated context or is dropped at projection time. Rationale: today only the first chunk of a question carries paper context; chunks 2..n of the same atom embed blind.

This honors atoms §8 ("atom-level chunking (primary)… page-aligned (secondary)") without a rewrite: the page-marker pass stays as the secondary fallback for any doc without group keys.

### 4.2 Matrix

Header grammar (pipe-delimited, single line, prepended to chunk text before embedding **and** mirrored into metadata columns — headers are for the vector, columns are for SQL; never parse headers in SQL):

```text
<corpus> | <subject qual/code> | <series year+session> | <paper code> | <unit/topic> | <q/page ref>
```

| # | Artifact | Chunk unit | Target tok | Max tok | Header example | Metadata (new cols) | Vector? | Store |
|---|---|---|---|---|---|---|---|---|
| 1 | **Specification** (parsed, per subject/unit) | one spec statement enriched with section path; sibling micro-statements (know-that one-liners) grouped under their subsection | 60–150 | 300 | `IGCSE Chemistry 4CH1 | Spec 1.22 | Unit 3 | Electrolysis` | kind=SPEC, spec_codes[], unit | Yes (thin) | documents + chunks + spine rows |
| 2 | **SME revision notes** (spec-mapped) | note section per spec point / tight sibling group; never split a point's explanation; split only at sub-headings when oversized | 200–450 | 800 | `IGCSE Chemistry | Notes | Spec 1.22–1.25 | Electrolysis of molten salts` | kind=NOTE, spec_codes[], unit, note_id | Yes (primary knowledge) | documents + chunks |
| 3 | **Textbook** (parsed, spec-mapped coarse) | heading section (full chapter→section path); paragraph-boundary splits when long | 400–800 | 1200 | `IGCSE Chemistry | Textbook <book-id> Ch 7.2 | Spec ~1.22–1.26 (coarse) | Electrolysis of aqueous salts` | kind=TEXTBOOK, spec_codes[] coarse + confidence, book_id | Yes (tier 3) | documents + chunks |
| 4 | **QP** (atoms → canonical bridge) | one chunk per atom (stem + all parts + choices rendered); split inside an atom only at block boundaries when oversized | 150–600 | 900 | `IGCSE Chemistry 4CH1 | Jun 2022 | 1C | Q3 | pp.5–6` | kind=QUESTION_PAPER, series(JAN/JUN/NOV), year, paper_code, atom_number, page_start/end, asset refs | Yes (evidence) | documents + chunks |
| 5 | **MS** (atoms → canonical bridge) | one chunk per atom: all mark points for that atom together + guidance + allow/reject + levels bands | 100–500 | 900 | `IGCSE Chemistry 4CH1 | Jun 2022 | 1C | MS Q3 | pp.3–4` | kind=MARK_SCHEME, same identity cols | Yes (evidence) | documents + chunks |
| 6 | **Question bank** (atoms `questions.json` rows) | **not chunked**; a **question card** is emitted instead: header + tags + one-line summary (≤60 tok) | ≤60 | ≤80 | `Q-Card | 4CH1 | Jun 2022 | 1C | Q3 | topics: electrolysis, half-equations | spec: 1.22,1.23` | full tags; links to bank row + paperDir#qN | Cards only | bank tables (SQL) + card vectors |
| 7 | **Assets** (figure crops) | not embedded in v2 | — | — | — | asset registry keyed by (paperDir, qnum, src) | No | assets table; referenced from chunk metadata |

Notes on the matrix:

- **Sizes** are token estimates (the chunker's `chars/4` proxy is fine); they're targets for packing within a unit, not hard splits of a unit — a 900-token atom stays one chunk (up to `max-tokens`), and the oversized path splits it at block boundaries, never at sentence mid-points the renderer would mangle.
- **Numbers for capacity planning** (chemistry): spec ≈ 300–500 chunks; notes ≈ 400–800; textbook ≈ 1,500–3,000 (per book); QP+MS ≈ 45–55 chunks/paper → ≈ 4,100–5,000 at 91 papers; cards ≈ 1,400+. Total ≈ **8–11k chunks**. Trivially cheap to (re)embed; this is why one-shot migrations are affordable and there is no excuse for incremental drift.
- **MCQ `choices` render into the QP chunk; correctness lives only in MS/bank** (`part.correct` for auto-scoring). This preserves the leak rule end to end.

---

## 5. What we do NOT embed (and why)

- **Full question+MS merged units.** Redundant with layers 4/5/6; pollutes the evidence layer with answer-bearing text (leak risk) and double-ranks every question (RRF). Cards + SQL assembly cover the use case.
- **Full question bank text.** Same as above; the bank is SQL. (This is an explicit correction of an older idea that may appear in repo docs — see §12.)
- **Per-mark-point MS chunks.** One-line fragments; meaningless alone; bloat the index ~5×.
- **Raw `answer_lines`, boxes, ticks.** Furniture, not content.
- **Images as vectors (v2).** No multimodal embeddings yet; alt text + captions are embedded where present; asset crops stay linked for the future upgrade.
- **Anything without `subject_id`.** A chunk that cannot be scoped cannot be retrieved; don't ingest it until it can.

---

## 6. Embedding policy & the one-shot v2 migration

**Model:** `gemini-embedding-001` (fix the queued `text-embedding-004` misconfiguration). Output truncated via MRL to **768d** to match the existing pgvector column and index — no dimension migration, smallest blast radius. (1536d/3072d is a valid future upgrade; it's §13.2, not now.)

**What v2 bundles (this is the "never migrate twice" list):**

1. Model switch (`gemini-embedding-001` @768d) — affects every row, so it defines the re-embed moment.
2. Per-chunk header projection in core (§4.1 step 4) — the bridge's section-level headers (v1.1.1) already exist; this completes them with page refs on every chunk.
3. New metadata columns on chunks: `subject_id`, `series`, `year`, `paper_code`, `atom_number`, `spec_codes`, `embed_rev`, `embed_model` (+ indexes: btree on `(subject_id, kind)`, `(subject_id, year, series)`, GIN on `spec_codes`).
4. New `Document.Kind` values: `NOTE`, `TEXTBOOK` (SYLLABUS already exists and maps to spec chunks).
5. Chunker atom-boundary mode (§4.1).
6. Ingest of: spec chunks + notes + bridge QP/MS (the 11 finished papers) + question cards.

**Mechanics:** every v2 row carries `embed_rev=2, embed_model='gemini-embedding-001'`. Reads filter `embed_rev = <current>` (single constant in the retrieval repo). rev1 rows are untouched until the eval gate passes, then deleted/superseded — including the 172 legacy docs — so RRF never sees both. Rollback = flip the constant back. Cost of re-embedding the full corpus (~8–11k chunks) is trivial; the expensive resource here is *coherence*, not embeddings.

**Idempotency:** reuse the existing document identity (parser `documentId` + `checksum` unique). Re-ingest of the same file updates/returns existing rows; v2 uses new `doc_version` values so rev1 rows are never mutated in place.

**Sequencing guard:** the bridge output is ready but **must not be POSTed before v2 lands**. The queued "ingest + Gemini key backfill" task is hereby blocked until P2 (§10) is done. This is the direct answer to the previously pending "ingest" step.

---

## 7. Retrieval & routing

**Mandatory scoping (all intents):** `subject_id = :course.subjectId` in every vector query; course/unit scoping (T-C07) adds a spec-range/unit predicate for scoped courses. The header text is *not* the filter; the columns are. Add a leakage test: query with out-of-subject terms must return zero out-of-subject chunks.

### EXPLAIN ("explain electrolysis", "why is the rate faster at higher temperature", "help me with Q3 from June 2022")

- Route: `KaRagService` unchanged in shape — KG leg (topic match → spec codes → sibling expansion) + vector leg over knowledge + evidence layers → RRF with **per-kind weights**: NOTE ≈ 1.0, SPEC ≈ 0.9 (scoping/router value), TEXTBOOK ≈ 0.7, QUESTION_PAPER ≈ 0.8 (verbatim question text), MARK_SCHEME ≈ 0.6 (only surfaced when answers are policy-allowed; CLA `ClaLeakagePolicy` governs), CARDS ≈ 0.3 (only as identity pointers).
- If the query names a paper/question ("June 2022 Q3"), the Fetch resolver runs first and pins that atom's QP chunk as must-include evidence.
- Citations: spec codes + `paper_code Qn pp.X–Y`. The atoms products carry page numbers on every block/point — hold the answer layer to citing them.
- Keep the deterministic refusal when evidence is empty — it is a feature, not a failure mode.

### FETCH ("give me the June 2019 paper 1C question 14 answer", "mark scheme for Jan 2020 2C Q7b")

- Route: deterministic parse (regex over series/year/paper/qnum vocabulary; LLM assist only on parse failure) → bank SQL → assemble QP text + MS points from bank/atoms by ID. **Zero vector calls on the happy path.** Gate: exact-match 100% on the Fetch golden subset; a miss falls back to evidence-layer vector search and is logged as a parse defect.

### ENUMERATE ("all photoelectricity questions from 2019 to 2024", "every 6-mark question on titrations since 2021")

- Route: SQL first — `questions ⋈ question_topics/spec_codes WHERE year BETWEEN … AND …` (+ marks/type filters when asked). Semantic fallback: card vectors filtered to the same year/subject window, unioned by (paper, qnum). Completeness comes from the SQL path; the card path only adds recall for vocabulary mismatch. Return the list with citations; do not generate prose summaries unless asked.

**Dedup rule (all intents):** results keyed by `(paper_code, atom_number, kind)` — one hit per key per kind; cards collapse into their bank row. Prevents the same question surfacing three times from card + QP chunk + note mention.

**Reranking:** stay with `NoReranker` + RRF weights for v2. Add a cross-encoder/LLM rerank **only if** the eval harness shows RRF+weights below gate (§9). Do not cargo-cult a reranker in.

---

## 8. Data hygiene prerequisites (blockers for the bank layer)

1. **Session canonicalization:** `series ∈ {JAN, JUN, NOV}` as a stored enum (display labels elsewhere). Map "Summer"/"June" → JUN, "October/November" → NOV. One migration; done before Enumerate ships, or year-range queries lie.
2. **Missing paper_codes (~10):** backfill from folder identity (the parser's folder path is the identity carrier); papers that genuinely lack codes get a deterministic surrogate (`<subject>-<series><year>-P?`) + a flag — never null.
3. **Missing MS (~10):** mark in the bank as `ms_unavailable`; the evidence layer ingests QP only; Enumerate shows "mark scheme not available" instead of silence.
4. **Legacy corpus retirement:** after rev2 passes eval, delete/supersede the 172 rev1 docs and their chunks (§6).
5. **Flagged atoms:** atoms with closure-affecting flags (`MS-POINTS-DONT-CLOSE` etc.) are ingested but carry flags into metadata; Enumerate can filter them; Smart Mark already sees them. No silent exclusion.

---

## 9. Eval harness (no retrieval change ships without it)

**Golden set (build once, version it, extend per subject):** ≥120 chemistry queries in three baskets —

- 40 EXPLAIN: natural questions + expected spec refs + (when paper-specific) expected (paper, qnum); judged on recall@10 of expected spec refs among retrieved chunks + citation validity (every citation resolves to a real doc/atom/page) + refusal correctness for out-of-scope queries.
- 40 FETCH: reference-style queries with exact expected (series, year, paper, qnum); gate: **100%** exact match (deterministic path) — any miss is a parser bug.
- 40 ENUMERATE: list queries with expected question-ID sets; gates: recall ≥ 0.95, precision ≥ 0.90 against the SQL+card path.

**Procedure:** store expected results as IDs, not prose; run the harness in CI against a seeded corpus; every gate must pass before cut-over and after any chunker/header/model/metadata change. Track the numbers in the worklog so drift is visible across runs.

---

## 10. Phased delivery

| Phase | Contents | Exit criteria |
|---|---|---|
| **P0 — Audit & archaeology** | §1 inventory + code audit; supersede banners on outdated docs; deltas reported | Inventory in worklog; this doc confirmed or corrected |
| **P1 — Index substrate** | metadata columns + Kinds + chunker atom-boundary mode + header builder + `gemini-embedding-001` + `embed_rev` read filter + leakage test | All unit/IT tests green; a seeded 2-paper corpus chunks atom-aligned (no chunk crosses an atom) |
| **P2 — Corpus v2** | ingest spec + notes + 11 bridge papers + cards (headers everywhere); embed rev2; golden set v1; eval run vs rev1 | Eval gates pass; rev1 kept until this point |
| **P3 — Routing** | subject predicate enforced; per-kind weights; Fetch + Enumerate endpoints; dedup rule; data hygiene (§8.1–8.3) | Fetch 100%; Enumerate gates; leakage test green |
| **P4 — Cut-over & scale** | supersede rev1 + legacy; backfill remaining papers as atoms land; textbook tier; coverage report v1; authoritative retrieval doc written, old docs marked superseded | Coverage report published; no rev1 rows; docs updated |

Phase 1 of tutor value = P2+P3 (spec + notes + 11 papers + routing). Textbooks are P4 by design, not by neglect.

---

## 11. Your task list (ordered, with report-back)

Work top to bottom; each task ends with a worklog append (Task ID, what changed, evidence) using the shared worklog at `/home/z/my-project/worklog.md`.

- **R1 (P0):** Docs archaeology + code audit (§1). Report: inventory table + confirmation/correction of A1–A10. Do not code before this is logged.
- **R2 (P1):** Substrate work (metadata cols, Kinds, chunker mode, headers, model fix, `embed_rev`). Report: schema diff + test names.
- **R3 (P2):** Corpus v2 ingest (spec, notes, bridge papers, cards) + golden set v1 + first eval run. Report: chunk counts per kind, eval table vs rev1.
- **R4 (P3):** Routing (subject predicate, weights, Fetch/Enumerate, hygiene §8.1–8.3). Report: endpoint list + eval numbers.
- **R5 (P4):** Cut-over (retire rev1), backfill queue for remaining papers, textbook tier, coverage report. Report: final eval + coverage summary.

**Questions you must answer back during R1** (they tune §4/§6 without changing its shape): exact pgvector index type/params in prod; whether `documents.canonical_json` blocks already carry something usable as `groupKey`; the actual configured embedding model string; how `question_topics` rows are currently sourced; whether any doc claims a different chunking contract than §4.

---

## 12. Anti-patterns to watch for in the repo docs (archaeology watch list)

If an older doc recommends any of these, mark it superseded with a pointer here:

1. Fixed-token-window chunking for papers (violates atoms §8; splits questions).
2. Embedding the full question bank / merged Q+MS units as the enumerate corpus.
3. One chunk per mark scheme point.
4. Global cosine retrieval with no subject/kind predicate; single global score threshold (0.15) as the only relevance control.
5. Re-embedding without an `embed_rev`/model version column (untraceable corpora).
6. LLM-extracted entity GraphRAG as a prerequisite for tutor quality.
7. Raw session strings ("Summer 2019") in filters.
8. Whole-MS-as-one-document or whole-paper-as-one-chunk blobs.
9. Header text as the *only* carrier of filterable metadata (headers are for vectors; columns are for SQL).
10. A reranker added before the eval harness exists.

---

## 13. Open decisions for the owner (not blockers for R1/R2)

1. **Gemini key on Render** (`SYLLABAI_EMBEDDING_GEMINI_API_KEY` → gemini-embedding-001 quota) — blocks P2 embed run, nothing else.
2. **Embedding dimension** — default 768 (MRL-truncated) for v2; revisit 1536d only with a measured retrieval gain on the golden set.
3. **Textbook licensing stance** — which books, internal-use confirmation, excerpt limits in answers.
4. **Figures/multimodal timing** — when to make image-based questions answerable (alt text now; multimodal embeddings later; no OCR-the-figure-in-the-loop hacks).
5. **MS visibility policy in chat** — confirm CLA attempt-gating extends to the new MS chunks in all tutor surfaces (recommended).
6. **Topic taxonomy authority** — controlled list (Edexcel topic codes + keywords) vs free-form `question_topics`; recommend controlled list for Enumerate matching + free-form allowed as display tags.
7. **Course scoping model for T-C07** — spec-range vs unit-list on the course row (recommend spec-range; it composes with `spec_codes` filters for free).

---

## Appendix — Grounding map (files the audit starts from)

| Concern | Path |
|---|---|
| Chunker (to be extended) | `syllabai-core/src/main/java/com/syllabai/content/ChunkingService.java` |
| Vector search + kind filter | `syllabai-core/.../content/ContentRetrievalService.java`, `ChunkVectorRepository.java` |
| Document/Kind/idempotency | `syllabai-core/.../content/Document.java`, `ContentIngestionService.java` |
| KaRAG pipeline | `syllabai-core/.../tutor/KaRagService.java` (+ `ReciprocalRankFusion`, `GraphKnowledgeRetriever`, `ContentVectorRetriever`) |
| KG spine | `syllabai-core/.../knowledge/*`, `resources/concept-graph/concept_edges.yaml` |
| Structured bank | `syllabai-core/.../assessment/*` (Question, QuestionPart, MarkPoint, QuestionTopic, ExamPaper) |
| Leak policy | `syllabai-core/.../cla/ClaLeakagePolicy.java` |
| Embedding provider | `syllabai-core/.../content/GeminiEmbeddingProvider.java`, `EmbeddingProperties.java` |
| Atoms schema + retrieval contract | `syllabai-parser` → `QPMS_ATOM_SCHEMA.md` §8 (mirror: `download/QPMS_ATOM_SCHEMA.md`) |
| Canonical bridge (QP+MS per paper) | `syllabai-parser` branch `tc17-work` @ `2959ec9` → `tools/pdflane/atoms_to_canonical.py`, `tests_atoms_canonical.py` |
| Shared worklog | `/home/z/my-project/worklog.md` |

