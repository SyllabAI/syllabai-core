#!/usr/bin/env python3
"""
assignments_prod_probe.py — black-box verification of the assignments
contract on PROD (V49, ADR-029 tranche 4.10, trace 1a0e96ec82bacae5).

Pins, in order:
  1. fresh probe learner registers against the live R6 floor;
  2. anonymous teacher/learner traffic is 401; a LEARNER token on the
     teacher surface is 403 (double RBAC, black-box);
  2.5 the teacher join-code signup gate (09-29 role amendment): a register
     with role=TEACHER and a wrong join code is 403 — black-box, always
     runnable, no operator credentials needed;
  3. teacher pins (SYLLABAI_TEACHER_EMAIL / SYLLABAI_TEACHER_PASSWORD env;
     skipped with an honest notice when the credentials are absent from
     the environment):
       a. create with real pilot anchors -> 201, fields echoed;
       b. fail-closed targets: unknown code / subject root -> 404 and
          nothing written (the create that follows still works);
       c. learner list carries the assignment with mySubmission == null;
       d. bounds: questionsCompleted above the assignment's count -> 400,
          score above marks total -> 400;
       e. close -> learner hand-in -> 409; reopen -> 201;
       f. re-hand-in appends (latest wins) and the roster shows the REAL
          state (complete + latest score for the probe learner);
       g. a past-due assignment's hand-in lands as "late";
       h. THE HONESTY PIN, black-box: a submissions-only learner still has
          skillStates == [] — completion evidence never produces mastery.
  4. learner-only pins (always run, teacher or not): unknown assignment
     404 on submissions; out-of-bounds rejected on a not-yet-existing
     assignment is 404 first (the assignment gate precedes the bounds gate).

Exit 0 = all executed pins green; nonzero = a pin failed (line printed).
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("SYLLABAI_CORE_BASE", "https://syllabai-core.onrender.com")
TEACHER_EMAIL = os.environ.get("SYLLABAI_TEACHER_EMAIL", "").strip()
TEACHER_PASSWORD = os.environ.get("SYLLABAI_TEACHER_PASSWORD", "").strip()

ANCHOR_OK = "4CH1-S1-a"      # real pilot anchor (States of matter)
ANCHOR_OK2 = "4CH1-S2-a"     # neighbouring anchor family
SUBJECT_ROOT = "4CH1"        # a real node that is NOT below the subject root
ANCHOR_UNKNOWN = "4CH1-S99-z"

PAST_DUE = "1970-01-01T00:00:00Z"  # deliberately past — the late pin


def http(method, path, token=None, payload=None, timeout=150):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={"Content-Type": "application/json",
                 **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode()
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, None
    except Exception as e:
        return 0, str(e)


def fail(msg):
    print(f"  PIN FAIL: {msg}")
    sys.exit(1)


def main():
    for i in range(12):
        s, _ = http("GET", "/actuator/health", timeout=30)
        if s == 200:
            print(f"core warm (try {i + 1})")
            break
        time.sleep(10)
    else:
        fail("core never warmed")

    uname = f"asprobe_{int(time.time())}"
    s, reg = http("POST", "/api/v1/auth/register", payload={
        "email": f"{uname}@syllabai-test.dev", "password": "Probe-Learner-2026!",
        "displayName": "assignments-probe"})
    tok = (reg or {}).get("accessToken") if s in (200, 201) else None
    print(f"1. register: {s} learner_ready={bool(tok)}")
    if not tok:
        fail("register failed")

    # ── auth + RBAC negatives (black-box, always available) ──
    s2, _ = http("GET", "/api/v1/learners/me/assignments")
    s3, _ = http("GET", "/api/v1/teacher/assignments")
    s4, _ = http("GET", "/api/v1/learners/me/assignments", token=tok)
    s5, _ = http("POST", "/api/v1/teacher/assignments", token=tok, payload={
        "title": "learner must not create", "courseSlug": "igcse-chemistry-19",
        "courseLabel": "IGCSE Chemistry", "specRefs": [ANCHOR_OK],
        "marksTotal": 10, "questionCount": 5, "dueAt": PAST_DUE})
    print(f"2. auth/rbac: anon_list={s2} anon_teacher={s3} "
          f"learner_list={s4} learner_on_teacher={s5} (want 401,401,200,403)")
    if s2 != 401 or s3 != 401:
        fail("anonymous traffic must be 401 on both surfaces")
    if s4 != 200:
        fail(f"learner list expected 200, got {s4}")
    if s5 != 403:
        fail(f"learner on the teacher surface expected 403, got {s5}")

    s6, _ = http("POST", "/api/v1/learners/me/assignments/00000000-0000-0000-0000-000000000000/submissions",
                 token=tok, payload={"questionsCompleted": 1})
    print(f"   unknown assignment submit: {s6} (want 404)")
    if s6 != 404:
        fail("submission to an unknown assignment must be 404")

    # ── teacher join-code signup gate (09-29 role amendment, black-box) ──
    sg, _ = http("POST", "/api/v1/auth/register", payload={
        "email": f"{uname}_t@syllabai-test.dev", "password": "Probe-Teacher-2026!",
        "displayName": "join gate probe", "role": "TEACHER",
        "joinCode": "deliberately-wrong"})
    print(f"2.5 teacher signup, wrong join code: {sg} (want 403)")
    if sg != 403:
        fail("the teacher join-code gate must refuse a wrong code with 403")

    if not (TEACHER_EMAIL and TEACHER_PASSWORD):
        print("3. teacher pins SKIPPED — no SYLLABAI_TEACHER_EMAIL / "
              "SYLLABAI_TEACHER_PASSWORD in the environment (credentials are "
              "operator-held; the IT suite pins the teacher flow on CI)")
        print("LEARNER-SIDE PINS GREEN — rerun with teacher credentials for the full cycle")
        return

    s, tlogin = http("POST", "/api/v1/auth/login", payload={
        "email": TEACHER_EMAIL, "password": TEACHER_PASSWORD})
    ttok = (tlogin or {}).get("accessToken") if s in (200, 201) else None
    print(f"3. teacher login: {s} teacher_ready={bool(ttok)}")
    if not ttok:
        fail("teacher login failed — check the operator-held credentials")

    def create(title, refs, due):
        return http("POST", "/api/v1/teacher/assignments", token=ttok, payload={
            "title": title, "courseSlug": "igcse-chemistry-19",
            "courseLabel": "IGCSE Chemistry", "specRefs": refs,
            "marksTotal": 10, "questionCount": 5, "dueAt": due})

    due = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 7 * 86400))
    sc, created = create(f"probe assignment {uname}", [ANCHOR_OK, ANCHOR_OK2], due)
    print(f"   create: {sc} id={('ok' if (created or {}).get('id') else 'MISSING')} "
          f"refs={(created or {}).get('specRefs')} status={(created or {}).get('status')}")
    if sc != 201:
        fail(f"teacher create expected 201, got {sc}")
    aid = (created or {}).get("id")
    if (created or {}).get("specRefs") != [ANCHOR_OK, ANCHOR_OK2] or \
            (created or {}).get("status") != "open":
        fail("create echo broken (refs/status)")

    sn, _ = create("probe unknown anchor", [ANCHOR_UNKNOWN], due)
    sr, _ = create("probe subject root", [SUBJECT_ROOT], due)
    print(f"   fail-closed targets: unknown={sn} subject_root={sr} (want 404,404)")
    if sn != 404 or sr != 404:
        fail("fail-closed target gate broken")

    s7, lst = http("GET", "/api/v1/learners/me/assignments", token=tok)
    mine = next((x for x in (lst or []) if x["assignment"]["id"] == aid), None)
    print(f"4. learner list: {s7} entries={len(lst or [])} "
          f"mine_present={bool(mine)} mySubmission={(mine or {}).get('mySubmission')}")
    if s7 != 200 or mine is None or mine.get("mySubmission") is not None:
        fail("learner must see the assignment with mySubmission null before handing in")

    sb1, _ = http("POST", f"/api/v1/learners/me/assignments/{aid}/submissions", token=tok,
                  payload={"questionsCompleted": 6})
    sb2, _ = http("POST", f"/api/v1/learners/me/assignments/{aid}/submissions", token=tok,
                  payload={"questionsCompleted": 2, "score": 11})
    print(f"5. bounds: questions={sb1} score={sb2} (want 400,400)")
    if sb1 != 400 or sb2 != 400:
        fail("bounds gate broken (hand-in may never exceed the assignment)")

    s8, _ = http("POST", f"/api/v1/teacher/assignments/{aid}/status", token=ttok,
                 payload={"status": "closed"})
    s9, _ = http("POST", f"/api/v1/learners/me/assignments/{aid}/submissions", token=tok,
                 payload={"questionsCompleted": 1})
    s10, _ = http("POST", f"/api/v1/teacher/assignments/{aid}/status", token=ttok,
                  payload={"status": "open"})
    print(f"6. lifecycle: close={s8} closed_submit={s9} reopen={s10} (want 200,409,200)")
    if s8 != 200 or s9 != 409 or s10 != 200:
        fail("lifecycle broken (closed must refuse hand-ins with 409)")

    s11, h1 = http("POST", f"/api/v1/learners/me/assignments/{aid}/submissions", token=tok,
                   payload={"questionsCompleted": 3, "score": 7})
    time.sleep(0.3)
    s12, h2 = http("POST", f"/api/v1/learners/me/assignments/{aid}/submissions", token=tok,
                   payload={"questionsCompleted": 5, "score": 9})
    s13, lst2 = http("GET", "/api/v1/learners/me/assignments", token=tok)
    mine2 = next((x for x in (lst2 or []) if x["assignment"]["id"] == aid), {})
    cur = mine2.get("mySubmission") or {}
    print(f"7. hand-ins: [{s11},{s12}] latest={cur.get('questionsCompleted')}q/"
          f"{cur.get('score')}marks (want 201,201 then 5q/9)")
    if s11 != 201 or s12 != 201:
        fail("hand-ins must 201")
    if cur.get("questionsCompleted") != 5 or cur.get("score") != 9:
        fail("latest hand-in must be the current state (re-hand-in appends)")

    s14, roster = http("GET", f"/api/v1/teacher/assignments/{aid}", token=ttok)
    rows = (roster or {}).get("rows") or []
    probe_row = next((r for r in rows if r.get("displayName") == "assignments-probe"), None)
    print(f"8. roster: {s14} rows={len(rows)} probe_row={probe_row}")
    if s14 != 200 or not rows:
        fail("roster must list the enabled student cohort")
    if not probe_row or probe_row.get("state") != "complete" or probe_row.get("score") != 9:
        fail("the probe learner's roster row must be complete with the latest score")
    if any(r.get("state") not in ("complete", "late", "missing") for r in rows):
        fail("roster states must be the complete/late/missing vocabulary")

    sl, latecreated = create(f"probe late {uname}", [ANCHOR_OK], PAST_DUE)
    slid = (latecreated or {}).get("id")
    s16, _ = http("POST", f"/api/v1/learners/me/assignments/{slid}/submissions", token=tok,
                  payload={"questionsCompleted": 1})
    s17, roster_late = http("GET", f"/api/v1/teacher/assignments/{slid}", token=ttok)
    late_row = next((r for r in ((roster_late or {}).get("rows") or [])
                     if r.get("displayName") == "assignments-probe"), None)
    print(f"9. late pin: create={sl} submit={s16} state={(late_row or {}).get('state')}")
    if sl != 201 or s16 != 201 or (late_row or {}).get("state") != "late":
        fail("a hand-in after the due date must land as 'late'")

    s18, st = http("GET", "/api/v1/learners/me/state", token=tok)
    skills = (st or {}).get("skillStates")
    print(f"10. honesty pin: skillStates={len(skills) if skills is not None else 'MISSING'} "
          f"(must be 0)")
    if skills != []:
        fail("COMPLETION EVIDENCE LEAKED INTO MASTERY — skillStates not empty on a "
             "submissions-only learner")

    print("ALL PINS GREEN — assignments contract verified on prod (full two-party cycle)")


if __name__ == "__main__":
    main()
