# Cycle-1 Pilot Deployment Runbook (T-031 minimal, ADR-009)

Scope: get the Edexcel IAL Chemistry retake pilot (~50 students, 8 weeks)
running on the free tier — Render (core) + Neon (Postgres) + Vercel (web) +
Cloudflare R2 (objects) + Groq/Gemini/OpenRouter (free LLM chain).

This is the operational companion to `render.yaml` (core blueprint) and the
Vercel project settings (web). Nothing here changes application code.

## 1. Provision order

1. **Neon** — create project, enable the `pgvector` extension
   (`CREATE EXTENSION IF NOT EXISTS vector;`), copy the JDBC connection
   string (`jdbc:postgresql://…?sslmode=require`).
2. **Render** — New → Blueprint → `SyllabAI/syllabai-core` (uses `render.yaml`).
   Fill the `sync: false` secrets in the dashboard. Replace the generated
   `SYLLABAI_JWT_SECRET` with an operator-chosen secret (min 32 chars) — the
   app **fails fast at boot when it is blank**, by design.
   First deploy: Flyway runs V1–V14, including the IAL Chemistry curriculum
   seed (V6) and the 8 demo MCQs (V7). Seeding is automatic — never run SQL
   by hand.
3. **Vercel** — import `SyllabAI/syllabai-web`, set the environment variable
   `NEXT_PUBLIC_API_BASE_URL=https://<render-service>.onrender.com` — the bare
   API origin **without** a trailing `/api/v1` (client paths already carry the
   `/api/v1` prefix; a trailing `/api/v1` is tolerated — the client normalizes
   it away — but the bare origin is the canonical form). It is inlined at
   **build time**: changing it requires a Vercel redeploy (verified 2026-09-10
   by inspecting the deployed JS bundle).
   **CORS — do not skip this:** the default allow-list covers only
   `https://syllabai.vercel.app` (project name `syllabai`), but the actual
   deployment is `https://syllabai-web.vercel.app` (project name `syllabai-web`)
   — **the default does NOT match** (empirically confirmed 2026-09-10:
   preflight from the real origin returns 403 until `SYLLABAI_CORS_ORIGINS`
   is set). On Render set:
   `SYLLABAI_CORS_ORIGINS=https://syllabai-web.vercel.app,http://localhost:3000`
   (add any custom domain or preview URL to the same comma-separated list).
   The web app itself will load fine without it — only browser sign-in fails,
   which makes the omission easy to misdiagnose.
4. **LLM keys** (optional per provider — the chain degrades to deterministic
   refusal when all are down): Groq first, Gemini, OpenRouter. Embedding key
   (Gemini) only if running the content pipeline.
5. **Cloudflare R2** (optional — only the GLM-OCR/ingestion pipeline uses
   object storage; the pilot student loop does not). The blueprint selects
   `SYLLABAI_STORAGE_TYPE=r2` unconditionally, but the app **boots fine
   without R2 secrets**: it logs a boot-time WARN, and any storage operation
   then fails loudly with the missing setting names (there is no silent
   fallback to local/ephemeral disk, by design). To enable R2: create a
   bucket (free tier: 10 GB), create an R2 API token, and fill
   `SYLLABAI_R2_ACCOUNT_ID` / `SYLLABAI_R2_ACCESS_KEY_ID` /
   `SYLLABAI_R2_SECRET_ACCESS_KEY` / `SYLLABAI_R2_BUCKET` in the dashboard.

## 2. Accounts

- **Students** self-register via the login screen — always the STUDENT role
  (Master Spec §6.1). No admin action needed.
- **Teacher account** (the pilot tutor): registration creates STUDENTs, so
  provision the teacher once, manually, against the running database:

  ```sql
  -- users table is bcrypt-hashed; the safe path is the provisioning helper:
  -- run locally with the prod database URL as a one-off (local profile):
  SYLLABAI_DATABASE_URL=jdbc:postgresql://… \
  SPRING_PROFILES_ACTIVE=local \
  java -jar target/syllabai-core-*.jar   # DemoUserSeeder is local-profile-only
  ```

  > **Warning:** `@Profile("local")` activates `DemoUserSeeder` against
  > **whatever database `SYLLABAI_DATABASE_URL` points at**. Pointing it at the
  > Render database seeds the full demo set — admin/teacher/student with public
  > demo passwords — into production. If this one-off path is used at all,
  > rotate every seeded password and delete the accounts that are not the
  > tutor's immediately after provisioning.

  For the pilot this is acceptable (documented, one account, password rotated
  after the pilot). A first-class teacher-invite flow is teacher-LMS scope
  (ADR-015, Cycle 2+).

- Demo accounts (`student@syllabai.dev` etc.) are seeded **only** by the
  `local` profile — a Render deployment does not seed them on its own. The one
  exception is the §2 one-off provisioning recipe above, which runs the local
  profile against the target database by design; that is exactly why it
  carries the rotation warning.

## 3. Pre-pilot verification checklist (T-032 gate inputs)

Run against the deployed Render URL (not localhost).

**Status 2026-09-10 (automated pass against `https://syllabai-core.onrender.com`,
12/12 via `scripts/t036_verify_deploy.py`):** every API item below passed;
the two web items remain pending the `SYLLABAI_CORS_ORIGINS` setting — see
`download/t036/RENDER_FAILED_DEPLOY_TRIAGE.md` for the full table.

- [ ] `GET /actuator/health` → `{"status":"UP"}` after cold start
- [ ] `POST /api/v1/auth/register` → 201 + token; login works
- [ ] Student loop: `/api/v1/questions` lists the 8 seeded MCQs;
      `POST /api/v1/attempts` (wrong answer) → evidence fires;
      `GET /api/v1/learners/me/state` shows the mastery move;
      `GET /api/v1/learners/me/recommendations` returns a ranked action
      citing the measured marks;
      `GET /api/v1/learners/me/attempts` lists the attempt.
- [ ] Tutor: `POST /api/v1/tutor/ask` with a moles question → grounded answer
      with citations (or an honest deterministic refusal if no LLM key set).
- [ ] Authorization: student token → `GET /api/v1/teacher/learners` = 403;
      unauthenticated → 401 (regression-proven in `TeacherRouteSecurityIT`).
- [ ] Content boundary: unvalidated GLM-OCR content never serves
      (regression-proven in `GlmOcrBatchIT` / `NextBestActionFlowIT`).
- [ ] Web: deployed Vercel URL loads, sign-in works, all six student tabs
      render their honest empty states before the first attempt.

## 4. Operational notes

- **Cold starts** (Render free tier): the first request after idle can take
  ~30–60 s. The web client surfaces "backend unavailable" honestly rather
  than spinning forever; retake students tolerate this — document it in the
  pilot onboarding message.
- **Nightly decay**: the `prod` profile enables the Ebbinghaus decay job
  (`application-prod.yml`). No cron config needed — it self-schedules.
- **Backups**: Neon free tier retains 7 days of PITR. The only irreplaceable
  pilot data is attempts/answers (evidence) — export weekly if the pilot's
  research data matters before the tier's retention window.
- **Scaling guard**: the pilot is ~50 students. Nothing here needs a paid
  tier; do not "fix" load with money before Cycle-1 evidence says so.
