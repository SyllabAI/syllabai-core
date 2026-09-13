# SyllabAI — Multi-Agent Engineering Rules (binding)

These rules exist because each one corresponds to an observed incident, not to a
hypothetical risk. Violations of these rules are workflow failures even when the
resulting code is correct.

## 1. Applied Flyway migrations are immutable

Once migration `Vn` has been applied to ANY database (campaign, test, or a
developer machine), its file is project history: it must not be edited,
renamed, deleted, reformatted, or comment-adjusted. Schema or documentation
changes go into a NEW migration `V(n+1)`.

- Incident precedent: V13__diagnosis_tutor_policy was modified in place after
  introduction (column + seed-data change), then renumbered V13→V14 in a rebase
  collision, then comment-edited again — each event invalidating recorded
  checksums on databases that had already applied it.
- Before creating a migration, inspect the remote `main` for the highest
  existing version number; migration numbers are allocated SERIALLY (one
  integrator, or a claim-then-write reservation file committed first). Two
  agents must never independently reserve the same number.
- Incident precedent: two agents concurrently created V15 (campaign identity vs
  concept nodes); resolution required renumbering one side to V16.

## 2. Database identity gates are fail-closed and mandatory

- The test suite may only target the disposable database `syllabai_test`
  (enforced globally by the JUnit Platform extension `RequireTestDatabase`,
  autodetected via the pom; a run that bypasses Maven must set the URL
  property itself). Silence is never interpreted as safety.
- Every script that can delete or rewrite campaign data MUST call
  `scripts/campaign_db_preflight.py` before touching a row. The gate verifies
  `current_database()` (not the connection string), refuses test databases,
  and requires the `campaign_db_identity` row to match the expected campaign
  label. Missing identity → fail closed.
- Callers must derive their actual working connection from the gate's verified
  target — never verify one database and operate on another.
- Every fail-closed gate ships with a negative test proving it fails on the
  wrong target and a positive test proving it permits the right one.

## 3. Campaign state that is not durably exported is not canonical project state

Ephemeral infrastructure (containers, /tmp, local PostgreSQL data dirs) may be
used for execution, but never as the sole store of project state or evidence.
Every campaign milestone produces, outside the ephemeral workspace:

- PostgreSQL data dump (the hashed object itself, not just its hash),
- row-count manifest, content/identity checksums,
- schema/migration history version, campaign DB identity,
- git commit SHAs (corpus, parser, core), verification result, timestamp.

A milestone without these artifacts is automatically YELLOW. Exports are the
exit path of state-mutating tooling, not a discretionary agent chore.

## 4. Claims carry evidence labels

Cross-agent reports must label claims:

- **VERIFIED** — directly demonstrated, backed by a surviving artifact.
- **INFERRED** — strongly supported, not directly proven.
- **REPORTED** — another agent claims it; not independently checked.
- **UNVERIFIED** — no surviving evidence.

Absence claims ("no artifact survived") require an exhaustive sweep before
they are made. Reviewers must not promote REPORTED to VERIFIED implicitly.

## 5. Identity ambiguity is quarantined, never auto-repaired

Documents with unresolved printed identity (duplicate cover dates, QP/MS
session disagreement, missing paper references) are quarantined with their
evidence preserved. Ingestion machinery must fail closed on nameless or
ambiguous identity. Resolution requires the original source or an operator
decision — never an agent inference.

Incident precedent: 1c-2016jan (QP duplicates January-2015; MS belongs to a
different paper) stays quarantined pending operator/PDF resolution.

## 6. The serving boundary is not negotiable

Imported assessment content starts SUGGESTED and becomes learner-servable only
through explicit teacher validation. No ingestion or repair tooling may
auto-validate, embed, or publish content. Zero implicit embeddings.

## 7. Throughput rule

Successful batches never stop the campaign. Automated gates run per batch and
the pipeline continues on green; only a genuine invariant, identity,
provenance, migration, data-loss, serving-boundary, or security failure halts
the workflow.

## 8. Canonical multi-agent coordination

The project-wide coordination contract lives in `SyllabAI/syllabai` under
`.syllabai/` and its root `AGENT.md`. When working in this repository, use that
coordination state in addition to these core-specific rules.

Required behaviors:

- Record the task ID, owner/surface and base commit before substantial work.
- Treat shared resources (especially Flyway versions and database schema) as
  serialized; never independently allocate a migration number.
- If `main` advances and touched files/contracts overlap, reconcile before
  completion and rerun affected tests.
- Preserve the distinction between T0/T1 authoritative truth and T3 agent
  suggestions.
- Material milestone claims require durable evidence; agent transcripts alone
  are not canonical evidence.
- Completion reports must distinguish VERIFIED / INFERRED / REPORTED /
  UNVERIFIED claims and state the next safe action.
