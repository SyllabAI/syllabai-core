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
- In-process PDF extraction: `opendataloader-pdf` (Apache-2.0, Maven Central)
- Deploy: Docker on **Render free tier** (no state on local filesystem; cold starts accepted)

## Modules (packages, not microservices — ADR-012)

```text
com.syllabai
├── identity          (auth, users, RBAC, consent)
├── curriculum        (boards, subjects, units, topics, versions)
├── knowledge         (KG nodes/edges, misconceptions, traversal)
├── content           (documents, canonical format, provenance)
├── assessment        (question bank, mark schemes, attempts, evidence)
├── smartmark         (AI marking, kappa gate, human overrides)
├── learner           (BKT, BDT, Ebbinghaus decay, learner state)
├── tutor             (KA-RAG orchestration, tutor policy, citations)
├── diagnostic        (struggle inference, interventions)
├── recommendation    (next-best-step)
├── teacher           (class views, review queues)
├── research          (learning-log telemetry, experiments, registries)
└── infrastructure    (LLM/vector/graph/storage adapters, jobs)
```

Each module owns its application services, domain objects, ports, and persistence adapters. Cross-module communication via domain events and contracts — never direct repository access.

## OOP expectations (graded course project)

Strategy (retrieval, interventions, assessment), Factory/Provider (LLM/embedding/parser adapters), Observer (telemetry events), Adapter (external engines), Repository, Specification. Meaningful responsibilities only — no pattern theater. See Master Spec §23.

## Status

Not started. Bootstrap task: **T-001** in the main repo's `TODO.md` (Wave 0).
