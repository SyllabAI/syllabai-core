# Learner Interaction Memory — Core Implementation Contract

This document is the implementation-facing companion to the canonical architecture in `SyllabAI/syllabai/LEARNER_INTERACTION_MEMORY_ARCHITECTURE.md`.

## Runtime boundary

Implement this feature inside the existing modular monolith. Do not create a second learner service or a separate memory database for Cycle 1.

The initial bounded contexts are:

```text
Tutor
  └── Conversation / Message

Learner Evidence
  └── InteractionEvidence

Learner / Diagnosis
  └── LearnerInteractionPattern

Recommendation
  └── consumes interaction patterns as one evidence source

Knowledge
  └── resolves evidence to canonical SpecificationPoint / KnowledgeNode
```

## First implementation slice

1. Persist conversation and message records with student + subject scope.
2. Add bounded thread summary support if the current Tutor implementation needs it.
3. Introduce an `InteractionEvidence` domain model and repository.
4. Add an extractor port with structured output, provider/model/prompt metadata and idempotency.
5. Resolve extracted concept candidates against the canonical curriculum/KG.
6. Store unresolved candidates rather than inventing canonical nodes.
7. Add asynchronous/after-turn pattern aggregation.
8. Expose a read-only learner-memory query to Tutor.
9. Add recommendation integration only after evidence/pattern behavior is tested.

## Do not do in the first slice

- no direct BKT formula changes;
- no new graph database;
- no automatic canonical misconception creation;
- no automatic teacher validation;
- no unbounded transcript injection into prompts;
- no cross-student semantic search;
- no provider-specific model calls from domain code;
- no blocking synchronous pattern rebuild after every token.

## Suggested extraction schema

```json
{
  "evidenceType": "MISCONCEPTION_CANDIDATE",
  "claim": "Student says they confuse oxidation and reduction.",
  "conceptCandidates": ["oxidation", "reduction"],
  "specificationPointCandidates": ["..."],
  "confidence": 0.91,
  "strength": "STRONG",
  "sourceMessageIds": ["..."],
  "requiresHumanReview": false
}
```

The application must validate this output. The JSON itself is never trusted as a domain command.

## Evidence lifecycle

```text
CANDIDATE
   ↓ normalization
ACCEPTED / REJECTED / UNRESOLVED
   ↓ aggregation
PATTERN
   ↓ learner policy
LEARNER STATE / DIAGNOSIS INPUT
```

A later message can contradict an earlier evidence item. Keep both observations; do not mutate historical evidence.

## Idempotency

A deterministic extraction key should include at least:

```text
student + source message/turn IDs + extractor version
```

Provider retries and job retries must be safe.

## Tests required

At minimum:

- student isolation;
- source-turn provenance;
- duplicate extraction idempotency;
- unknown concept does not create KG node;
- weak signal does not directly change mastery;
- repeated strong signals form a pattern;
- contradictory signals remain auditable;
- Tutor retrieves only authorized/relevant learner memory;
- provider failure does not corrupt conversation history;
- model/provider/prompt metadata is retained for derived evidence;
- recommendation consumes interaction evidence without bypassing the existing evidence policy.

## Operational priority

The implementation should be delivered incrementally. A working conversation store plus a small, well-tested evidence extractor is more valuable than a large generic memory framework.
