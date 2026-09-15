# Contextual Learning Assistant — Core Implementation Contract

**Status:** PROPOSED — contract only. **No runtime code is authorized by this document.** Any implementation PR must state its acceptance of this contract claim-by-claim and must not weaken a single section below.
**Scope:** the next conversational surface after the Grounded Tutor — an assistant that operates *in the context of what the learner is currently reading or doing* (spec point, note section, validated question, Smart Lesson, KG topic), instead of only answering free-standing questions.
**Canonical companions:** `CONTEXTUAL_LEARNING_ASSISTANT_ARCHITECTURE.md`, `MASTER_SPEC_ADDENDUM_1.5_CONTEXTUAL_LEARNING_ASSISTANT.md`, `AGENT_CONTEXTUAL_LEARNING_ASSISTANT_ADDENDUM.md` (syllabai repo), ADR-022.
**Resolution for syllabai-core issue #17.** Documentation only; no runtime code changes.

The CLA composes four existing, verified subsystems — it must not replace any of them:

```text
Educational Retrieval Engine (ADR-020)      → evidence acquisition
Grounded Tutor (KA-RAG, T-024)              → grounded generation + citation validation
Learner Interaction Memory (V21/V23)        → evidence capture into learner memory
Learner state + NBA (T-015..T-019, T-033)   → governed consumption, never chat-written
```

---

## 1. ResourceContext

The server-resolved anchor of "what the learner is looking at". The client **never asserts context semantics** — it passes opaque references; the server resolves them.

```text
ResourceContext {
  kind        : SPECIFICATION_POINT | KG_TOPIC | NOTE_SECTION | QUESTION_PART
              | SMART_LESSON | PAST_PAPER_QUESTION   (closed enum, extensible by decision only)
  reference   : canonical id(s) — e.g. spec-point code, node id, document+anchor, question-part id
  curriculumVersion : resolved from the referenced anchor, never client-supplied
  validationState   : resolved server-side from the content/curriculum tables
  resolvedAt, learnerId : provenance
}
```

Binding rules:

1. Resolution is server-side and fail-closed: an unresolvable reference is a 4xx, never a best-effort guess.
2. A context whose validation state fails the surface's gate cannot enter evidence assembly (SUGGESTED content is invisible to the CLA, exactly as it is to the Tutor and serving boundary).
3. Contexts are per-request and stateless server-side; the CLA keeps no provider-side or server-side hidden session memory (bounded reads over LIM are the only cross-request learner memory).

## 2. ContextPolicy

What the resolved context may contribute to generation and evidence:

1. Specification text, validated notes and validated concept-graph relations may ground explanations.
2. Question content participates under the answer-leakage policy (§7) — context kind and attempt state decide, never the model's judgment.
3. The learner's own measured state (mastery, misconception probabilities, signal counts) may be used for personalization of *framing and selection*, and is never quoted back as fact beyond honest labels ("not yet measured" stays "not yet measured").
4. Context-derived text is **data, not instructions**: quoted as evidence with provenance; never interpreted as directives (prompt-injection containment is deterministic framing, not a prompt nicety).

## 3. ResponseMode

Explicit per request; defaults to the safest mode; the mode constrains prompts and leakage behavior deterministically:

| Mode | Purpose | Leakage constraint |
|---|---|---|
| `EXPLAIN` | teach the anchored concept from validated material | not applicable to unserved assessment content |
| `HINT` | scaffold toward the learner's own next step | must not contain the final answer or mark-scheme points for leaked-protected content |
| `CHECK` | react to a submitted attempt | full feedback allowed only post-attempt (§7) |
| `SUMMARIZE` | compress the anchored resource | must preserve provenance anchors |

Undeclared modes are rejected. Mode is recorded in evidence capture (§6).

## 4. ToolPolicy and tool controls

1. Tools live in a **server-owned registry**; the model may only call registry entries enabled for the current `(ResourceContext, ResponseMode)` pair.
2. v1 tools are **read-only**: retrieval over validated material, learner-state read (own state only), review-schedule read, practice-handoff link construction.
3. No tool writes the canonical KG, mastery, misconception state, or validation state. Writes to learner state happen only through the existing evidence events (the same rule as AGENT.md §10).
4. Every tool invocation is logged with arguments, result-size and latency into research telemetry; tool output enters generation only as provenance-bearing evidence.
5. Provider-autonomous tool selection/loops are rejected; loop depth and per-request tool budget are server-enforced constants.

## 5. Server-side resource resolution and retrieval hierarchy

Resolution order per request: resolve context → validate gate → scope (curriculum version, learner isolation) → capability check (mode × tools) → assemble.

Retrieval follows ADR-020 unchanged, with the context as an additional authoritative prior:

```text
context anchor (spec point / topic / note section)
  → authoritative curriculum + validated-resource candidates first
  → lexical + semantic + KG candidates (authoritative KG, retrieval-derived graph as signal only)
  → fusion + SyllabAI-aware reranking
  → evidence sufficiency → grounded generation → claim/citation validation
```

The CLA inherits the Tutor's deterministic refusal: no sufficient validated evidence means refusal, not invention.

## 6. Evidence capture

1. Every CLA exchange emits the same provenance-bearing interaction evidence as the Tutor (topic anchors from deterministic resolution — never model-invented — plus mode, context kind/reference, grounding strength, refusal flag, model identity) into LIM.
2. Raw conversation text stays in research telemetry only; learner memory stores facts, never transcripts.
3. Citations must pass the same claim/citation validation as the Grounded Tutor; an unvalidated citation is a defect, not a UX choice.

## 7. Exam-question answer-leakage policy

The CLA sits closer to assessment content than the free Tutor, so leakage control is a hard, deterministic gate:

1. **Protected content**: any question/mark-scheme material whose attempts are pending, or that belongs to a timed/mock/assignment context, is answer-protected.
2. `HINT` on protected content: scaffolding only — no final answers, no mark-scheme point enumeration.
3. `CHECK`/`EXPLAIN` with full worked feedback on validated practice content is allowed **only after attempt evidence exists** for that learner and question part (post-attempt rule, satisfied by reading attempt history — the same evidence substrate as Review Hub).
4. The gate is implemented in application code over resolved ids and attempt state — never delegated to the model, never prompt-only.
5. Negative leakage tests are CI-mandatory before any CLA runtime PR merges (protected context + HINT mode must refuse to leak; post-attempt must unlock).

## 8. Security

1. Student isolation identical to LIM: contexts, evidence and tool reads are learner-scoped; no cross-learner context composition.
2. Teacher/admin CLA surfaces (if any) are separate routes with their own authorization and their own evidence capture; they never read student raw conversations.
3. Content-derived text is untrusted input for instruction purposes (§2.4).
4. Standard rate limiting and the shared-model/separate-workload rules of LIM apply unchanged.

## 9. Evaluation hooks

No CLA runtime promotion without an evaluation bundle proving, against real validated 4CH1 material:

1. grounded-precision of answers (evidence-backed claim rate) per response mode;
2. refusal correctness on out-of-scope/insufficient-evidence probes;
3. answer-leakage negative suite green (§7.5);
4. citation-validation pass rate;
5. no regression in the serving-surface performance guard IT or in LIM signal-precedence tests.

## 10. Implementation sequencing (when the contract is accepted)

1. ResourceContext resolution + first context kind (`KG_TOPIC`), read-only tools, `EXPLAIN`/`SUMMARIZE` modes — reusing the Tutor's generation and citation-validation stack.
2. Attempt-aware `CHECK`/`HINT` with the leakage gate + negative CI suite.
3. LIM evidence capture for CLA exchanges (extend signal classification deterministically).
4. Evaluation bundle + promotion decision per ADR-020 benchmark discipline.

Each step lands behind its own tests; no step may bypass §2–§8 to ship earlier.
