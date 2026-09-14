# Contextual Learning Assistant — Core Implementation Contract

**Date:** 2026-09-14  
**Status:** Design contract; implementation to follow after product prioritization

## 1. Purpose

This contract maps the SyllabAI Contextual Learning Assistant product design onto the core runtime without creating a separate AI stack.

The feature should extend the existing Tutor/provider architecture with typed context and policy boundaries.

## 2. Suggested runtime objects

### `ResourceContext`

Server-derived, immutable for the duration of a request.

Minimum fields:

- resource type;
- resource ID;
- student ID / authorization scope;
- subject;
- qualification;
- exam board;
- specification/version;
- SpecificationPoint IDs;
- KG node IDs;
- validated resource content or selected content slices;
- assessment context where applicable;
- relevant learner-state/evidence references.

The client may supply an opaque resource ID. The server resolves the actual context and verifies access.

### `ContextPolicy`

Defines what the model may treat as primary context and how to behave when the request is outside that context.

Initial modes:

- `PAGE_LOCKED`
- `SYLLABAI_ROUTING`
- `GENERAL_EDUCATIONAL` (policy-gated)

### `ResponseMode`

Examples:

- `REVISION_NOTE_DEFINITIONS`
- `REVISION_NOTE_SUMMARY`
- `REVISION_NOTE_PITFALLS`
- `REVISION_NOTE_EXAM_HELP`
- `REVISION_NOTE_FREE_CHAT`
- `EXAM_QUESTION_UNDERSTAND`
- `EXAM_QUESTION_APPROACH`
- `EXAM_QUESTION_FREE_CHAT`

### `ToolPolicy`

Explicit allow-list of tools for the current mode, student and resource.

### `EvidenceCapture`

Controls whether and how the turn is passed to the Learner Interaction Memory pipeline.

## 3. Resource resolution

Do not accept educational truth from the browser.

Bad pattern:

```text
browser → subject + specification + question + mark scheme → LLM
```

Required pattern:

```text
browser → authorized resource ID
       ↓
server resolves canonical/validated resource
       ↓
ResourceContext
       ↓
ContextPolicy + ToolPolicy
       ↓
Tutor/LlmProvider
```

## 4. Retrieval rules

Retrieval may expand the context but cannot override authoritative anchors.

The runtime should prefer:

1. current validated resource;
2. linked SpecificationPoints;
3. canonical KG relationships;
4. validated assessment evidence;
5. relevant learner evidence;
6. retrieval results;
7. general model knowledge.

Vector similarity must never be treated as educational truth.

## 5. Tooling

Initial candidate tools:

- current-resource lookup;
- specification context lookup;
- related KG concept lookup;
- curriculum/resource search;
- revision-note lookup;
- exam-question lookup;
- learner-state lookup;
- relevant-learning-evidence lookup;
- start practice;
- open similar question;
- navigate to resource.

The model proposes tool calls. Application code validates authorization, student scope, resource scope, arguments and action policy before execution.

## 6. Learner Interaction Memory

A contextual assistant turn is not automatically learner truth.

The conversation is persisted as history. Educationally meaningful turns may produce candidate `InteractionEvidence` with:

- student identity;
- conversation/turn identity;
- resource/question identity;
- SpecificationPoint/KG linkage;
- evidence type;
- confidence;
- source = contextual assistant;
- model/provider;
- prompt/policy version;
- created timestamp;
- idempotency key.

Evidence aggregation remains separate from raw chat persistence.

## 7. Exam-question behavior

Exam Question mode should support progressive help:

```text
understand → hint → approach → guided work → attempt → Smart Mark → improve
```

The assistant should not pretend to be the canonical marking engine. Smart Mark remains responsible for mark-scheme-aware candidate generation and deterministic validation.

Answer leakage should be measured and controlled by product policy.

## 8. Security

Mandatory controls:

- student isolation;
- resource authorization;
- server-derived context;
- structured tool schemas;
- no arbitrary URL/action tools;
- resource/tool results treated as data, not instructions;
- no direct canonical KG writes from LLM output;
- no direct mastery writes from LLM output;
- no teacher-validation bypass.

## 9. Failure behavior

Provider failure must be explicit. The system must not fabricate an answer because a contextual provider failed.

If context resolution fails, fail closed or fall back to a clearly less-contextual Tutor mode according to policy; never silently claim the current resource was used when it was not.

If a tool fails, report the limitation and continue only if the response remains safe and grounded.

## 10. Evaluation hooks

The implementation should make it possible to measure:

- resource-context grounding;
- SpecificationPoint alignment;
- off-topic routing;
- answer leakage;
- tool-call correctness;
- student isolation;
- evidence-extraction precision;
- latency;
- provider failure rate;
- learner improvement after contextual assistance.

## 11. Non-goals

Do not introduce:

- a separate contextual-assistant LLM deployment by default;
- a separate learner model;
- a separate chat knowledge graph;
- direct LLM writes to canonical educational state;
- unrestricted autonomous browsing/action.

## 12. Implementation sequencing

Recommended order:

1. typed `ResourceContext` and server-side resolution;
2. `ContextPolicy` / `ResponseMode`;
3. Revision Note contextual prompts and quick actions;
4. Exam Question contextual prompts and progressive-help policy;
5. bounded navigation/resource tools;
6. Interaction Evidence capture;
7. evaluation/telemetry;
8. only then broader agentic capabilities.
