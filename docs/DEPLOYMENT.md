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

### 1b. Campaign identity env vars (added 2026-09-14, session 67)

The production database is now the **canonical campaign DB** (the full 4CH1
corpus was ingested through the sanctioned campaign machinery — release
`t-031-prod-ingestion`). Two env vars keep the campaign-identity claim stable
across every boot/spin-down (the app's `CampaignDbIdentity` upserts the row
at startup; without these it flips to `UNCLAIMED` on wake, which the
fail-closed campaign preflight refuses):

- `SYLLABAI_CAMPAIGN_LABEL=T-C04-CAMPAIGN`
- `SYLLABAI_CAMPAIGN_COMMIT=<current core short SHA>`

**LLM keys (operator action pending):** the provider keys
(`SYLLABAI_GROQ_API_KEY` first, `SYLLABAI_GEMINI_API_KEY` /
`SYLLABAI_OPENROUTER_API_KEY` fallbacks, `SYLLABAI_EMBEDDING_API_KEY`
content-pipeline-only) were wiped from the Render service by an env-var API
misuse on 2026-09-14 (a partial PUT replaced the full set — disclosed in the
session-67 tracker entry). Everything else was restored and re-verified; the
tutor endpoint honestly returns 503 until these are re-provisioned in the
Render dashboard. The rest of the product is unaffected.

**API gotcha recorded:** `PUT /v1/services/{id}/env-vars` REPLACES the entire
set — always send the complete list, never a partial one.

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

- **Cold starts** (Render free tier): the instance spins down after ~15 min
  idle and the next request pays a full JVM + Spring boot. Mitigations landed
  2026-09-23 (see "Free-tier load strategy" below) — the visible worst case
  for a waking request is now an honest "waking up" banner in the web client
  while the request completes, typically well under a minute with AppCDS.
  Retake students tolerate this — document it in the pilot onboarding message.
- **Nightly decay**: the `prod` profile enables the Ebbinghaus decay job
  (`application-prod.yml`). No cron config needed — it self-schedules.
- **Backups**: Neon free tier retains 7 days of PITR. The only irreplaceable
  pilot data is attempts/answers (evidence) — export weekly if the pilot's
  research data matters before the tier's retention window.
- **Scaling guard**: the pilot is ~50 students. Nothing here needs a paid
  tier; do not "fix" load with money before Cycle-1 evidence says so.

### Free-tier load strategy (2026-09-23)

The complaint was "Render takes lots of time to load" and the worry was
blowing the monthly free limit / losing the account. What was done — and the
ground rules:

**Web client (syllabai-web):**
1. Content GETs (subjects, knowledge tree, question lists, prerequisites,
   concept-graph edges, exam-paper browse/detail) are cached in localStorage
   (stale-while-revalidate, 6 h TTL). A returning student's repeat visit
   paints from cache and **never wakes the sleeping instance** — the single
   biggest saver of instance-hours. Teacher content mutations
   (validate/place/reject/flag/topic-map/activate) drop the cache.
2. Identical in-flight GETs are de-duplicated (one network round-trip even
   under React StrictMode double-mounts).
3. ONE unauthenticated `GET /actuator/health` per browser session, fired when
   a human lands on the login screen — the boot happens while they type
   credentials. Tied to a real page view, guarded by sessionStorage.
4. Requests slower than 3 s (with no recent success) raise an honest
   "server waking up (free tier)" banner; the first dropped connection on a
   cold GET is retried once automatically.
5. Per-user read models (state, attempts, recommendations, knowledge graph,
   smart lesson, tutor/CLA) are NEVER cached — they must reflect live evidence.

**Core (syllabai-core):**
1. `application-prod.yml`: response compression for JSON (question payloads
   are large), banner off, graceful shutdown inside Render's SIGTERM window.
2. Dockerfile: AppCDS with a BUILD-TIME TRAINING RUN (session-122) — the
   image boots the real app once during `docker build` (DB pointed at an
   unreachable localhost socket, Flyway off, Hibernate bootstrapped without
   a live connection, exit right after context refresh) and bakes the class
   archive into the image layer at `/app/cds/app.jsa`. `start.sh` pre-seeds
   `/tmp/syllabai-appcds.jsa` from the baked copy on every container start,
   so EVERY wake maps the archive — including the first boot after a deploy.
   This replaced the runtime-only `-XX:+AutoCreateSharedArchive` dump
   (session-121), which never engaged: a spin-down that does not end in an
   orderly JVM exit writes no archive, and the measured cold wake on that
   build (2026-09-23 15:52 UTC) was 184.6 s — at/above the 105–175 s
   pre-AppCDS baseline. Any clean runtime exit still re-dumps a
   full-coverage archive over the /tmp copy (top-up). Non-fatal at every
   step: the training run is timeout/||-guarded (verified: the dump is
   written even on non-zero JVM exits), a missing/corrupt archive just means
   no CDS.
3. `render.yaml`: `previewsEnabled: false` — PR previews would each run a
   free instance around the clock and silently eat the budget.

**Ground rules (the "don't get banned" part):**
- **NEVER add an uptime pinger / keep-alive cron / 5-minute health timer.**
  Render's Terms of Service prohibit artificially defeating free-tier
  spin-down; services kept perpetually awake that way risk account
  suspension. The ONLY sanctioned scheduled wake is the web repo's
  once-daily Vercel cron (`/api/cron/keep-alive`, now `"0 3 * * *"`) that
  wakes the instance inside the 03:00 UTC decay window — one bounded wake a
  day (~15–30 instance-hours/month) with a documented functional purpose;
  do not extend it or add others. Since V38 (session-114) the decay pass is
  ledger-guarded (checked every 15 min, runs iff the window's
  `decay_job_runs` row is absent), so even a missed keep-alive costs
  schedule precision, not correctness — any next wake completes the night.
  Availability monitoring (6-hourly probe) lives in the public
  `SyllabAI/syllabai-ops` repo — that cadence reports on real availability
  and must not creep up into keep-alive territory.
- **Why the keep-alive cron moved from `"50 2 * * *"` to `"0 3 * * *"`
  (session-122):** Vercel Hobby cron jobs trigger once per day WITHIN THE
  SCHEDULED HOUR — the minute field is not honored (documented Hobby
  limitation, re-verified against 2026-09 platform docs). A fire anywhere in
  02:00–02:44 wakes the instance for only Render's ~15 min of idle budget,
  so it sleeps again before the 03:00 checker tick. Measured: all three
  windows since V38 (Sept 21–23) executed as CATCH_UP ~2 h late via the
  (itself 3–5 h queue-delayed) pilot-monitor probe, not via the keep-alive.
  Scheduling the wake inside the 03:00 hour lands it after the window
  anchor: the first checker tick after the boot completes the window —
  typically executed 03:03–03:20. `SCHEDULED` (rather than `CATCH_UP`) needs
  the instance up before 03:00:00 sharp, which within-the-hour semantics
  cannot target; the ledger's `executed_at` is the source of truth for the
  t1 audit either way.
- **Budget math:** free tier = 750 instance-hours/month. One continuously
  awake instance would burn ~730 h — right at the edge, which is exactly why
  pingers are both prohibited and pointless. Real pilot traffic (dozens of
  students, sessions spread over the day) plus the daily decay-window wake
  lands far below the cap; the web client's content cache keeps idle
  browsing off the instance entirely.
- **Region:** keep the Neon project near `frankfurt` (the Render region) —
  Flyway's migrations (V1–V38 today) run their checksum validation at every
  boot and pay the round-trip latency to the database on each one.

## 5. Pilot monitoring (added 2026-09-13, session-58)

> **Moved 2026-09-16 (session 77):** the `pilot-monitor.yml` workflow left
> this repo's `.github/workflows/` and now lives in the public
> `SyllabAI/syllabai-ops` repo (byte-identical probe, same 6-hourly + weekly
> crons) — private-repo Actions minutes were quota-dead since 2026-09-15.
> The description below is preserved for the operational contract.

Minimum operational monitoring, per the session-57/58 pilot-readiness
conditions. No new observability system — it rides existing capability
(GitHub Actions cron in the `syllabai-web` repo + the `DISCORD_WEBHOOK_URL`
secret that already exists there):

- **`.github/workflows/pilot-monitor.yml`** (web repo) — every 6 h, plus a
  weekly ops run Sunday 09:33 UTC, executes `scripts/ops/pilot_probe.py`
  against the deployed production URLs and posts a green/red report to the
  existing Discord channel. Probed: backend health (cold-start tolerant),
  CORS from the real web origin, auth-failure mode (401 not 5xx), anonymous
  teacher-route guard, deployed **web-bundle freshness** (the four pilot UI
  markers — subject-scoped practice, v1.1 cards, ConceptGraphView, 4CH1 UI),
  the learner loop smoke (login → subjects → practice list → recommendations
  with the pinned `nba-rules/v1.1` policy), subject-scoping + missing-param
  regressions (f7adea7 / 5991187), and **4CH1/WCH11 contamination** (an
  activated 4CH1 must serve zero questions). Teacher-surface checks
  (concept-graph read + idempotent re-activation) activate automatically once
  the `PILOT_TEACHER_*` secrets are set after the tutor account exists.
- **`PILOT_MONITOR_EMAIL` / `PILOT_MONITOR_PASSWORD`** (web repo secrets) —
  credentials of the `pilot.monitor@syllabai-test.dev` learner account
  (TEST-classified, session-58), used read-only by the probe.
- **`scripts/ops/export_evidence.sh`** (this repo) — the weekly evidence
  export: run with `NEON_DSN` from the operator's credentials; writes CSVs +
  a sha256 manifest for attempts, users, user_roles, skill_states,
  misconception_states, review_schedules. The weekly Discord report carries
  the reminder.
- **κ / structured assessment**: reported as N/A by the probe until T-C04
  ships validated structured content; the field becomes a real check then.
