# CLA Runtime Acceptance — claim-by-claim (steps 1–2)

**Commits:** step 1 `c97dec1` (+`6983d7c` test fix); step 2 `87f0acc` (+`6b19882`/`6c3e400`/`544bad1` — paper-less subject resolution, registry enablement, self-contained gate assertion)
**Contract:** `docs/CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md` (PROPOSED → its §10.1 + §10.3 now implemented; the contract document remains the governing text and its HINT/CHECK / leakage-gate / evaluation-bundle claims stay unimplemented until their own steps)
**Scope:** one vertical slice — `POST /api/v1/learners/me/cla/ask` with kind `KG_TOPIC`, modes `EXPLAIN`/`SUMMARIZE`, read-only tool composition, and interaction-evidence capture into LIM.

This PR states its acceptance of the contract claim-by-claim, per the contract header's requirement. Nothing below weakens the contract.

## §1 ResourceContext

| Claim | Status | Evidence |
|---|---|---|
| Server-resolved anchor; client passes opaque references only | **ACCEPTED (implemented)** | `ClaContextResolver.resolveKgTopic` resolves subject + topic + curriculum identity server-side from `(rootId, topicNodeId)`; the request carries no context semantics |
| Fail-closed: unresolvable reference is a 4xx, never a guess | **ACCEPTED (implemented)** | every resolution failure is `NotFoundException` (404): non-subject root, topic outside subtree, unknown id — pinned by `ClaContextResolverTest` |
| Closed kind enum, extensible by decision only | **ACCEPTED (implemented)** | `ResourceContext.Kind` names the full closed set; the runtime resolves only `KG_TOPIC` in this step |
| curriculumVersion resolved from the anchor, never client-supplied | **ACCEPTED (implemented)** | resolved from the owning subject's `CurriculumVersion` (`SubjectRepository.findByKnowledgeNodeId`, added in this PR) |
| validationState resolved server-side; gate blocks evidence assembly | **ACCEPTED (implemented)** | non-`VALIDATED` topic → 404 indistinguishable from unresolvable (no validation-state oracle) — `ClaContextResolverTest.unvalidatedTopicFailsClosed`, `ClaFlowIT.contextResolutionFailsClosed` |
| Contexts per-request, stateless; no hidden session memory | **ACCEPTED (implemented)** | `ClaService` holds no state; only LIM windowed reads carry cross-request learner memory |
| SUGGESTED content invisible to the CLA | **ACCEPTED (implemented)** | same validation gate as above; vector retrieval already searches validated corpus only (same `ContentRetrievalService` as the Tutor) |

## §2 ContextPolicy

| Claim | Status | Evidence |
|---|---|---|
| Spec text / validated content grounds explanations | **ACCEPTED (implemented)** | anchor KG node + validated chunks are the only evidence sources (hybrid retrieval, unchanged ADR-020 stack) |
| Question content under the §7 answer-leakage policy | **DEFERRED (contract sequencing §10.2)** | step 1 serves no question/assessment context kind; the leakage gate lands with HINT/CHECK in step 2 with its CI-mandatory negative suite |
| Learner's own measured state personalizes framing/selection only, honest labels | **ACCEPTED (implemented)** | `GET_LEARNER_STATE` reads the learner's OWN states scoped to the anchored topic + prerequisites; the brief renders measured values or honest absence; unmeasured stays unmeasured |
| Context-derived text is data, not instructions | **ACCEPTED (implemented)** | all context enters generation through the same evidence/citation framing the Tutor uses (provenance-bearing SOURCES block); no free-text context injection channel exists |

## §3 ResponseMode

| Claim | Status | Evidence |
|---|---|---|
| Explicit per request; undeclared modes rejected | **ACCEPTED (implemented)** | `ResponseMode` enum binding; unknown values → 400 (`HttpMessageNotReadableException` handler added; `ClaFlowIT.httpFailSafe`) |
| Mode constrains prompts deterministically | **ACCEPTED (implemented)** | `ClaService.modePlan` rewrites the intervention plan per mode deterministically (EXPLAIN / SUMMARIZE / HINT / CHECK); the plan is injected into the prompt by the SAME generator the Tutor uses |
| Mode recorded in evidence capture | **ACCEPTED (implemented)** | `tutor_topic_engagements.response_mode` + telemetry payload `mode` — `TutorEngagementRecorderTest.claInteractionWritesProvenanceRows`, `ClaFlowIT.learnerEvidenceWithProvenance` |
| `HINT` — scaffolding only; must not contain final answers or mark-scheme points (§7.2) | **ACCEPTED (implemented, step 2)** | `ResponseMode.HINT` + `ClaLeakagePolicy`: mark-scheme document chunks and scheme-point evidence are deterministically excluded from HINT evidence assembly — `ClaLeakagePolicyTest`, `ClaFlowIT.hintNeverLeaksMarkScheme` (live: sources = [QUESTION_PAPER, KNOWLEDGE_NODE], zero MARK_SCHEME) |
| `CHECK` — full feedback only post-attempt (§7.3) | **ACCEPTED (implemented, step 2)** | `ResponseMode.CHECK` gated by `ClaLeakagePolicy.checkModeAdmission` over attempt history — `ClaFlowIT.checkPreAttemptRefuses` (409, zero generator calls) + `checkPostAttemptUnlocks` (real attempt via the assessment pipeline unlocks) |

## §4 ToolPolicy and tool controls

| Claim | Status | Evidence |
|---|---|---|
| Server-owned registry; model may only call enabled entries | **ACCEPTED (implemented)** | `ClaToolRegistry.enabledFor(kind, mode)` is the only enablement path and is invoked per request; step-1 composition is fixed server-side — the provider selects nothing (LIM §3.10) |
| v1 tools read-only | **ACCEPTED (implemented)** | `GET_SPECIFICATION_CONTEXT`, `GET_RELATED_CONCEPTS`, `GET_LEARNER_STATE` call only read paths of `KnowledgeGraphService` / `LearnerModelService`; the class has no write-capable dependency; behavior pinned by `ClaFlowIT.noCanonicalOrMasteryMutation` (KG rows, edges, skill/misconception states unchanged across a CLA exchange) |
| No tool writes canonical KG / mastery / validation state | **ACCEPTED (implemented)** | as above; the only write on the whole CLA path is the LIM engagement append via the existing governed recorder |
| Every invocation logged (args, result-size, latency) into research telemetry | **ACCEPTED (implemented)** | `ToolTrace` on every invocation → `ClaInteractionEvent.tools` → `telemetry_events.CLA_EXCHANGE_COMPLETED.tools` — `ClaFlowIT.learnerEvidenceWithProvenance` asserts the trace lands |
| Provider-autonomous tool selection rejected; loop depth / budget server-enforced | **ACCEPTED (implemented)** | no loop exists; the per-request tool budget IS the fixed 3-tool composition (server-decided, not model-decided) |

## §5 Server-side resolution + retrieval hierarchy

| Claim | Status | Evidence |
|---|---|---|
| resolve → validate gate → scope → capability check → assemble | **ACCEPTED (implemented)** | exact order in `ClaService.contextualAsk`; scope = subject subtree isolation + learner isolation |
| Retrieval follows ADR-020 unchanged; context as authoritative prior | **ACCEPTED (implemented)** | same `VectorRetriever` / `ReciprocalRankFusion` / `EvidenceReranker` beans; the resolved anchor is ALWAYS the leading KG candidate (`ClaServiceTest.anchoredAsk`), so the context cannot be displaced by similarity noise |
| Deterministic refusal on insufficient validated evidence | **ACCEPTED (implemented)** | the grounding gate is inherited verbatim. HONEST STRUCTURAL NOTE: for `KG_TOPIC` step 1 the refusal branch is unreachable (the validated anchor itself is evidence — an anchored ask always has ≥1 validated source); thin-grounding refusals become exercisable with chunk-anchored context kinds in step 2. Stated here rather than pretended otherwise. |

## §6 Evidence capture

| Claim | Status | Evidence |
|---|---|---|
| Every exchange emits the same provenance-bearing evidence as the Tutor | **ACCEPTED (implemented)** | `ClaInteractionEvent` → `TutorEngagementRecorder.onClaInteraction` → rows with deterministic anchors, `evidence_count`, `refused`, `answer_model`, deterministic `signal_type` (SAME precedence list — never LLM classification) |
| Topic anchors from deterministic resolution — never model-invented | **ACCEPTED (implemented)** | anchors are the server-resolved context reference; the pipeline hardcodes `matchedTopicIds = [context.reference()]` |
| Raw conversation stays in research telemetry only | **ACCEPTED (implemented)** | `tutor_topic_engagements` gains NO text column (asserted by schema check in `ClaFlowIT`); the question text + tool trace live only in `telemetry_events` |
| Citations pass the same claim/citation validation as the Tutor | **ACCEPTED (implemented)** | the same `CitationResolver` bean resolves citations from the same `EvidenceItem` provenance — no CLA-specific citation path exists |

## §7 Exam-question answer-leakage policy

**IMPLEMENTED (step 2, contract §10.2)** — deterministic, application-code gate (`ClaLeakagePolicy`), never prompt-only (§7.4):

1. **Protected content** — the gate arms on `PAST_PAPER_QUESTION` contexts resolved through the full serving boundary (`ServableQuestionService.isServable`: validated current version + paper-level integrity gate); attempt state read from attempt history (Review Hub substrate) per learner+question (§7.1/§7.3).
2. **HINT** — mark-scheme evidence is excluded deterministically in every attempt state; scaffolding directives only (§7.2). `ClaLeakagePolicyTest.schemePointEvidenceMatrix`.
3. **CHECK/EXPLAIN/SUMMARIZE full feedback** — allowed only after attempt evidence exists; the unlock is the attempt-state read (§7.3). `ClaFlowIT.checkPostAttemptUnlocks` over the REAL assessment pipeline.
4. **Gate placement** — `checkModeAdmission` runs BEFORE retrieval and generation; pre-attempt CHECK never touches a provider, evidence assembly or learner memory (409 `attempt_required`). Pinned non-vacuously: `ClaServiceTest.questionCheckPreAttemptRefuses` asserts zero vector-retriever and zero generator calls.
5. **Negative leakage suite (CI-mandatory, §7.5)** — `ClaLeakagePolicyTest` (full deterministic matrix: 4 modes × attempt states × evidence sources) + `ClaFlowIT` over real Postgres/HTTP: HINT end-to-end carries no MARK_SCHEME source; CHECK pre-attempt 409 with zero generation; post-attempt unlock; HTTP 409 body `attempt_required`. GREEN in core-ci run `34950281437`-lineage (final: run on `544bad1`).

Document-chunk granularity decision (recorded honestly): canonical mark-scheme CHUNKS are page-level and cannot be bound to one question deterministically — on question contexts they are excluded entirely (strict-safe, any mode, any attempt state). Post-attempt full feedback grounds on the question's OWN VALIDATED assessment-model scheme points (`mark_schemes`/`mark_points`, question-granular, id-resolved) instead. KG_TOPIC evidence behavior is unchanged (tutor parity; a free topic discussion is not anchored to assessment content).

## §8 Security

| Claim | Status | Evidence |
|---|---|---|
| Student isolation identical to LIM | **ACCEPTED (implemented)** | learner-surface route under `/api/v1/learners/me` (authenticated); `GET_LEARNER_STATE` reads own state only, scoped to the context; no cross-learner composition path exists |
| Teacher/admin CLA surfaces | **N/A in step 1** | none built |
| Content-derived text is untrusted for instruction purposes | **ACCEPTED (implemented)** | evidence enters generation only as cited SOURCES data (same framing as the Tutor's §2.4 containment) |
| Rate limiting / shared-model separate-workload rules | **ACCEPTED (carried)** | the CLA reuses the tutor-grounded prompt verbatim as the SAME workload (registered in `model_versions` as `cla-contextual/1.0.0` with the prompt-reuse recorded); no new LLM workload is introduced; app-level rate limiting remains the platform's existing posture |

## §9 Evaluation hooks

**DEFERRED (contract sequencing §10.4):** the grounded-precision evaluation bundle, refusal-correctness probes and citation-validation pass-rate measurement over real validated 4CH1 material come with the promotion step. Step 1 claims `IMPLEMENTED` for the runtime slice and **does not claim** the contract's overall `VERIFIED` evaluation bar. What IS verified for step 1: 23 new/extended unit tests + `ClaFlowIT` (real Postgres/HTTP) covering context correctness, fail-closed resolution, authorization, tool boundaries, grounding shape, LIM provenance and no-mutation; the serving-surface performance guard IT remains green (CLA adds no SQL to the guarded surfaces' hot paths — one tree read, one prerequisite read, one misconception read, one batched learner-state read, one vector search per ask).

## §10 Sequencing

- **§10.1 — implemented (step 1)** (ResourceContext + KG_TOPIC + read-only tools + EXPLAIN/SUMMARIZE on the Tutor's generation/citation stack).
- **§10.3 — implemented (step 1)** (LIM evidence capture for CLA exchanges via the extension rules: answered event, deterministic record-time classification, surface + context identity provenance).
- **§10.2 — implemented (step 2, `87f0acc` lineage)** (attempt-aware HINT/CHECK + the deterministic §7 leakage gate + the CI-mandatory negative suite; question-anchored contexts resolve through the exact serving boundary).
- **§10.4 (evaluation bundle + promotion)** — remaining. The CLA contract's overall status remains driven by that step; nothing here promotes the complete CLA past its evidence.

## Status summary

- **Step 1 (KG_TOPIC + EXPLAIN/SUMMARIZE + read-only tools + LIM evidence): IMPLEMENTED / VERIFIED** — core-ci GREEN + live verification 14/14 (`evidence/cla_step1_live_verification_6983d7c.log`).
- **Step 2 (PAST_PAPER_QUESTION + HINT/CHECK + §7 leakage gate + negative suite): IMPLEMENTED / VERIFIED** — core-ci GREEN on `544bad1` (52/52 incl. ClaFlowIT negative suite) + live verification 9/9 with the real LLM (`evidence/cla_step2_live_verification_544bad1.log`): HINT zero mark-scheme citations on a real 4CH1 question (paper 4CH0/2C), CHECK 409 pre-attempt → 200 post-attempt through a real structured attempt, LIM signalCounts closed loop.
- **Step 4 (evaluation bundle + promotion) + further context kinds: NOT IMPLEMENTED** — remaining contract-governed work. No `VERIFIED` claim is made for the complete CLA.

---

## Step-3 addendum (post-§10.4): SPECIFICATION_POINT context kind

**Status: IMPLEMENTED / VERIFIED (core `6a89815` lineage + this change).** The
first contract-defined extension kind beyond steps 1–2. The client passes the
spec-point CODE it is displaying (an opaque string, e.g. "4CH1-1.18") plus the
subject root; the server resolves it to a VALIDATED curriculum node inside
that subject's subtree and anchors the SAME deterministic pipeline as KG_TOPIC
(anchors → spec-structure evidence → tools → leakage gate → LIM capture).

| Claim | Status | Evidence |
|---|---|---|
| §1.1 server-side, fail-closed resolution by code | ACCEPTED (implemented) | `ClaContextResolver.resolveSpecificationPoint` — unknown code / foreign subject's code → indistinguishable 404; pinned by `ClaContextResolverTest` |
| §1.2 validation gate | ACCEPTED (implemented) | a code mapping to a non-VALIDATED node is a 404 indistinguishable from unknown (live-probed through the CHM mirror subject's unvalidated topics) |
| subject isolation | ACCEPTED (implemented) | the code must resolve INSIDE the rooted subtree; foreign codes are 404 (unit + IT + live) |
| same evidence/tool/leakage spine | ACCEPTED (implemented) | `ClaService` dispatch adds the kind; tool registry enablement extended; `ClaServiceTest.specificationPointAnchorsPipeline` |
| evidence capture records the kind | ACCEPTED (implemented) | `context_kind=SPECIFICATION_POINT` flows through the existing `ClaInteractionEvent` → LIM columns |
| HTTP contract | ACCEPTED (implemented) | `ClaFlowIT.specificationPointFlow` — 200 with echoed kind/code, 404 unknown/foreign, 400 missing code |
| evaluation bundle coverage | ACCEPTED (implemented) | S-A probes extended: resolves by code + unknown-code fail-closed |

Honest remainder: NOTE_SECTION remains NOT IMPLEMENTED — the runtime has no
note-content substrate to anchor (documents are QP/MS/SYLLABUS/OTHER only);
building one is a content-model decision, not a CLA-slice change.
SMART_LESSON and QUESTION_PART remain contract-governed future kinds.

---

## Step-5 addendum: QUESTION_PART context kind (part-level anchor)

**Status: IMPLEMENTED / VERIFIED (core `d0dc00a` lineage).** The part-level
contract-defined kind: the client passes the opaque part id (plus, optionally,
the subject root its UI is scoped to); the server resolves part → version →
question → serving gate → paper/subject → VALIDATED primary topic through the
canonical assessment FKs, and CHECK feedback sees the learner's OWN submitted
work plus the part-scoped marking evidence.

| Claim | Status | Evidence |
|---|---|---|
| §1.1 canonical-relationship resolution (no free-text inference) | ACCEPTED (implemented) | `ClaContextResolver.resolveQuestionPart` — every hop a canonical FK; unit-pinned in `ClaContextResolverTest` (happy path asserts kind/reference/partLabel/stem/marks/attempted) |
| unknown part / unknown question / unvalidated content → indistinguishable 404 | ACCEPTED (implemented) | `partOfUnservableQuestionFailsClosed`, `unknownPartFailsClosed`, live G3 (404, no existence oracle) |
| invalid relationship (part of a superseded version) → 404 | ACCEPTED (implemented) | relationship gate: the part's version must BE the question's CURRENT version — `partOfSupersededVersionFailsClosed` |
| foreign-subject reference → 404 | ACCEPTED (implemented) | an explicitly supplied rootId belonging to another subject is rejected — `partWithForeignRootFailsClosed` (unit) |
| §7 gate parity (QUESTION_PART is a question context) | ACCEPTED (implemented) | `isQuestionContext()` covers the kind: CHECK pre-attempt deterministic 409 BEFORE generation (live G5, generator-call-count pinned in IT), mark-scheme DOCUMENT chunks excluded in every mode/attempt state, HINT never receives scheme points (`ClaLeakagePolicyTest` matrix) |
| part-scoped marking evidence | ACCEPTED (implemented) | post-attempt CHECK grounds on the anchored part's VALIDATED scheme points + question-level points; sibling parts' points never enter SOURCES — `ClaService.partAllowsPoint`, pinned non-vacuously in `ClaServiceTest.partCheckSchemeEvidenceIsPartScoped` (seeded sibling point) and `ClaFlowIT` (real entities) |
| CHECK sees the learner's OWN submitted work | ACCEPTED (implemented) | `EvidenceSource.LEARNER_WORK` — latest attempt resolved by learner+question ids; part-scoped on QUESTION_PART (`partAllowsAnswer`); live-found gap fixed in `d0dc00a` (the model previously could not perform the mode's stated job) |
| LIM evidence carries the part identity | ACCEPTED (implemented) | `contextKind=QUESTION_PART`, `contextReference=partId`, `nodeId` = the question's primary topic — live G7 + `ClaFlowIT` row assertions |
| no canonical KG / mastery mutation | ACCEPTED (inherited) | the read-only pipeline is unchanged; `ClaFlowIT.noCanonicalOrMasteryMutation` still green |
| HTTP contract | ACCEPTED (implemented) | missing partId → 400; unknown part → 404; real part → 200 (live G4/G3/G2) |
| web panel (contract: expose part-level context) | ACCEPTED (implemented) | web `1f22113`: question selector + part selector in the SAME Assistant panel; meta row renders `QUESTION_PART (a)`; 409 gate guidance verified in-browser |
| evaluation bundle coverage | ACCEPTED (implemented) | S-G set: 7/7 live over real validated 4CH1 material (canonical record 37/37 GREEN — VERIFIED, `.syllabai/evidence/cla/cla_eval_questionpart_d0dc00a.json`) |

Honest remainder: NOTE_SECTION remains NOT IMPLEMENTED — SUBSTRATE-BLOCKED /
architecture decision required (no note-content model exists in the runtime;
building one is a content-model decision). SMART_LESSON remains a
contract-governed future kind. Defects found by live verification and fixed
during this slice (all in product code, none by gate-weakening):
LazyInitializationException on part/answer entity traversals (OSIV off,
non-transactional service — fixed via scalar projection + read-only scalar FK
columns + eager entity graph), a derived JPQL query that failed named-query
validation at boot (fixed as native SQL over the stable FK columns), and the
missing learner-work evidence for CHECK feedback.
