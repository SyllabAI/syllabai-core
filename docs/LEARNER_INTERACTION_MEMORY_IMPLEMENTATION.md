# Learner Interaction Memory — Core Implementation Contract

**Status:** IMPLEMENTED (V21/V23 runtime, battery-verified surfaces) + binding extension rules
**Scope:** the learner-memory side of every conversational surface — Tutor today, the Contextual Learning Assistant (see `CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md`) tomorrow
**Canonical companions:** `LEARNER_INTERACTION_MEMORY_ARCHITECTURE.md`, `MASTER_SPEC_ADDENDUM_1.4_LEARNER_INTERACTION_MEMORY.md`, `AGENT_LEARNER_INTERACTION_MEMORY_ADDENDUM.md` (syllabai repo); this file is the core-side implementation binding.
**Resolution for syllabai-core issue #16.** Documentation only; no runtime code changes.

---

## 1. Definition and purpose

Learner Interaction Memory (LIM) is the bounded, provenance-bearing memory layer that turns conversational interactions into structured, per-topic learner signals — without ever storing raw chat text inside learner memory.

The invariant it enforces:

```text
Raw conversation (audit/history, research telemetry only)
        ≠
Extracted interaction evidence (provenance-bearing, structured, append-only)
        ≠
Derived learner state (mastery, misconceptions, struggle, review)
```

LIM is the middle layer. It receives *facts already computed by deterministic pipeline stages*, classifies them deterministically, and appends them as signal rows. It never interprets LLM output as truth, never mutates the canonical knowledge graph, and never writes mastery directly.

## 2. Implemented runtime (what exists in this repository today)

### 2.1 Data model

- `learner.tutor_topic_engagements` (`TutorTopicEngagement`): one row per (learner, matched topic, ask). Columns: `learner_id`, `node_id`, `occurred_at`, `evidence_count`, `refused`, `answer_model`, `signal_type`, timestamps.
- The raw question text is **deliberately absent** from the entity and the table. It exists only in the research telemetry log (`research.TelemetryEvent`), which is audit/history data.
- Append-only by design: no update or delete path exists for engagement rows.

### 2.2 Event flow (V21 P7 → V23)

1. `POST /api/v1/tutor/ask` → `KaRagService` runs the deterministic intent matcher (VALIDATED nodes only), KG+vector retrieval, grounded generation.
2. Every answer emits a `TutorAnsweredEvent` carrying: `learnerId` (null on anonymous preview), `matchedTopicIds` (**deterministic matcher output only — never LLM-invented**), `occurredAt`, `evidenceCount`, `refused`, `answerModel`, and the deterministic `interventionType` from `TutorPolicyService` (null on refusal).
3. `TutorEngagementRecorder` (`@Order(30)`, transactional) classifies the ask into exactly one V23 signal type and appends one row per matched topic:
   - `MISCONCEPTION_RELATED` — the tutor policy intervened on an active BDT misconception (rule output over measured learner state);
   - `DOUBT_SIGNAL` — explicit confusion phrase in the learner's own words (fixed substring list);
   - `EXPLANATION_REQUEST` — explanation command vocabulary;
   - `TOPIC_ENGAGEMENT` — default topic-match fact.
   Precedence is pinned by unit tests. The raw question is read and discarded inside the recorder.
4. Anonymous previews (`learnerId == null`) and asks with no deterministic topic match write **nothing**.

### 2.3 Consumers (all read-only, all learner-scoped)

- `LearnerStateController` — grouped per-topic `signalCounts` over a 30-day window.
- `SmartLessonService` — tutor-engaged leg and the V23 advance leg, bounded by `LearnerProperties.tutorEngagementWindowDays`; doubt/misconception-classified asks are named honestly in the reason and evidence trace; the standing rule (first unstarted, then first weak, curriculum order) remains the fallback.
- NBA engine — T7a tutor-engagement tier (policy `nba-rules/v1.2`, live since `26fec63`).

### 2.4 Production posture

The recorder is event-driven: engagement rows exist only when real tutor asks happen. Tutor generation requires provisioned provider keys; when keys were absent (the disclosed env-var incident), the tutor leg failed invisibly and fresh learners accumulated no engagement rows — this is expected fail-quiet behavior of the *provider* path, not a defect of the memory contract, but any agent diagnosing "empty engagement data" must first check provider provisioning (pilot probe F-1/P1 covers this).

## 3. Binding rules (enforced; issue #16 text is authoritative)

1. **Raw chat ≠ learner truth.** Raw conversation text is research telemetry/audit. It never enters learner-memory tables, learner-state computation, or serving decisions.
2. **LLM output is candidate evidence, never authoritative.** Only deterministic pipeline outputs (intent matcher topic ids, tutor-policy intervention plan, fixed pattern lists) may back a signal row. A model's claim about topic, misconception or mastery is not evidence.
3. **Provenance is mandatory.** Every row carries its grounding strength (`evidence_count`), refusal flag, answering-model identity, and deterministic `signal_type`. A future signal type that cannot carry provenance is not accepted into this table.
4. **Idempotency boundary.** The recorder is append-on-event with no dedupe because `TutorAnsweredEvent` is published exactly once per ask inside the answer transaction. Any future consumer that can observe redelivery (async transport, retries, outbox) MUST add a dedupe key before writing; append-only does not mean duplicate-tolerant-by-ignorance.
5. **Student isolation.** Every read path is `learnerId`-scoped at the repository level. No surface may aggregate one learner's interaction memory into another learner's view. Teacher/research aggregates require the evidence-lineage rules of `QUESTION_ATTEMPT_AND_LEARNING_EVIDENCE.md`.
6. **No canonical KG mutation from chat.** Chat may read the knowledge graph; nothing on the conversational path may create, relabel, validate or delete canonical nodes or edges.
7. **No direct mastery mutation.** Conversational evidence influences the learner only through the governed pipeline (evidence → learner-model logic → state). There is no chat-triggered arithmetic on mastery/misconception probabilities, and the rejected brainstorming rules (`flag → −0.02`, `resolve → +0.01`, self-doubt halving) remain forbidden here too.
8. **Bounded Tutor memory.** Consumers read engagement memory through explicit windows (`tutorEngagementWindowDays`, 30-day state counts). Memory is bounded by semantics (per-topic ask facts), not by storing transcripts. Any new consumer must declare its window; unbounded "all history" reads require an explicit architecture decision.
9. **Shared-model / separate-workload policy.** The free-LLM chain (`LlmChainConfig`, Groq → Gemini → OpenRouter) is shared infrastructure. Tutor generation, Smart Mark candidate generation, extraction and embeddings are **separate workloads**: separately prompt-pinned, separately telemetry-tagged, independently replaceable, and none may silently consume another workload's prompt, budget or output. A provider incident in one workload must never corrupt another workload's evidence.
10. **Application-controlled agentic tools.** Any tool-calling surface (today none; tomorrow the Contextual Learning Assistant) must expose a server-owned tool registry and policy. Provider-autonomous tool selection, provider-side memory, or provider-side session state are rejected — the platform owns context assembly, memory and tool boundaries (see `CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md`).

## 4. Extension rules (how new conversational surfaces attach)

A new conversational surface (CLA, voice, teacher-facing assistant) attaches to LIM by:

1. emitting (or reusing) an answered/interacted event carrying deterministic topic anchors and provenance;
2. classifying deterministically at record time (extend the precedence list; never classify with an LLM);
3. appending rows that obey §3 (provenance, isolation, bounded reads);
4. adding its consumers as read-only, windowed, honestly-labeled readers.

Anything that cannot be expressed within these constraints is a Master-Spec-level architecture change and needs a decision record first.

## 5. Verification obligations for future changes

- Signal-precedence unit tests must keep pinning MISCONCEPTION_RELATED > DOUBT_SIGNAL > EXPLANATION_REQUEST > TOPIC_ENGAGEMENT.
- Refused asks keep their topic rows (the unresolved signal); tests must keep asserting this.
- The serving-surface performance guard IT (`34bd963`) bounds SQL statement counts on learner surfaces; new LIM consumers must not regress it.
- Any schema change follows AGENT.md §1 (applied migrations immutable; new Flyway versions only).
