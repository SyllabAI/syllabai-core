# CLA Step-1 Runtime Acceptance — claim-by-claim

**Commit:** `cla: step-1 runtime slice — KG_TOPIC ResourceContext, read-only tools, EXPLAIN/SUMMARIZE, LIM evidence (V24)`
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
| Mode constrains prompts deterministically | **ACCEPTED (implemented)** | `ClaService.modePlan` rewrites the intervention plan per mode deterministically (EXPLAIN: teach-from-sources; SUMMARIZE: compress + preserve anchors); the plan is injected into the prompt by the SAME generator the Tutor uses |
| Mode recorded in evidence capture | **ACCEPTED (implemented)** | `tutor_topic_engagements.response_mode` + telemetry payload `mode` — `TutorEngagementRecorderTest.claInteractionWritesProvenanceRows`, `ClaFlowIT.learnerEvidenceWithProvenance` |
| HINT/CHECK deferred | **DEFERRED (contract sequencing §10.2)** | deliberately absent until the §7 gate exists — declaring them now would be an unsafe promotion |

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

**DEFERRED (contract sequencing §10.2) — and structurally inert in step 1:** no QUESTION_PART / PAST_PAPER_QUESTION context kind and no HINT/CHECK mode is servable in this step, so there is no leakage surface to gate yet. The CI-mandatory negative suite lands with step 2, before any attempt-aware mode merges. NOT IMPLEMENTED here; NOT weakened.

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

- **§10.1 — implemented by this PR** (ResourceContext + KG_TOPIC + read-only tools + EXPLAIN/SUMMARIZE on the Tutor's generation/citation stack).
- **§10.3 — implemented by this PR** (LIM evidence capture for CLA exchanges via the extension rules: new answered event, deterministic record-time classification, surface + context identity provenance).
- **§10.2 (attempt-aware HINT/CHECK + leakage gate + negative CI suite)** — next; **§10.4 (evaluation bundle + promotion)** — after that. The CLA contract's overall status remains driven by those steps; this PR promotes nothing beyond its own evidence.

## Status summary

`CLA step-1 runtime slice: IMPLEMENTED / VERIFIED (unit + IT evidence above)` — the broader CLA (steps 2–4) remains `PROPOSED`-governed work in progress. No `VERIFIED` claim is made for HINT/CHECK, the leakage gate, question/note context kinds, or the evaluation bundle.
