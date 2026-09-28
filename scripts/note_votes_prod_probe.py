#!/usr/bin/env python3
"""
note_votes_prod_probe.py — black-box verification of the note-vote evidence
class on PROD (V48, ADR-029 tranche 4.9, trace 1a0e95906dd17959).

Pins, in order:
  1. fresh probe learner registers against the live R6 floor;
  2. happy path: three votes on real pilot note anchors record (201), with
     both wire vocabularies accepted ("helpful"/"up", "down"/"not-helpful")
     and the canonical form echoed back;
  3. fail-closed attribution: unknown anchor -> 404, non-structure anchor
     (the subject root code) -> 404, garbage vote -> 400, anonymous -> 401;
  4. /state carries noteVotes (3, newest first) — and the ratings slice is
     untouched by vote traffic;
  5. THE HONESTY PIN, black-box: a votes-only learner has skillStates == []
     — self-report never produces mastery;
  6. append semantics: changing the vote on the same note appends (4 events
     total) and the LATEST event per note is its current vote.

Exit 0 = all pins green; nonzero = a pin failed (the failing line is printed).
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "https://syllabai-core.onrender.com"

ANCHOR_OK = "4CH1-S1-a"      # real pilot note anchor (States of matter)
ANCHOR_OK2 = "4CH1-S1-b"     # neighbouring anchor
SUBJECT_ROOT = "4CH1"        # a real node that is NOT below the subject root
ANCHOR_UNKNOWN = "4CH1-S99-z"


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

    uname = f"nvprobe_{int(time.time())}"
    s, reg = http("POST", "/api/v1/auth/register", payload={
        "email": f"{uname}@syllabai-test.dev", "password": "Probe-Learner-2026!",
        "displayName": "note-vote-probe"})
    tok = (reg or {}).get("accessToken") if s in (200, 201) else None
    print(f"1. register: {s} learner_ready={bool(tok)}")
    if not tok:
        fail("register failed")

    def vote(note, v, anchor, t=tok):
        return http("POST", "/api/v1/learners/me/note-votes", token=t, payload={
            "noteId": note, "vote": v, "subtopicCode": anchor})

    # ── happy path (both wire vocabularies, canonical echo) ──
    s1, r1 = vote("rn_probe001", "helpful", ANCHOR_OK)
    time.sleep(0.1)
    s2, r2 = vote("rn_probe002", "up", ANCHOR_OK)
    time.sleep(0.1)
    s3, r3 = vote("rn_probe003", "down", ANCHOR_OK2)
    print(f"2. votes: [{s1},{s2},{s3}] "
          f"({(r1 or {}).get('vote')},{(r2 or {}).get('vote')},{(r3 or {}).get('vote')}) "
          f"anchors=({(r1 or {}).get('subtopicCode')},{(r3 or {}).get('subtopicCode')})")
    if (s1, s2, s3) != (201, 201, 201):
        fail("happy-path votes did not 201")
    if (r1 or {}).get("vote") != "helpful" or (r2 or {}).get("vote") != "helpful" \
            or (r3 or {}).get("vote") != "not-helpful":
        fail("vote vocabulary mapping broken (up/down must map to helpful/not-helpful)")
    if (r1 or {}).get("subtopicCode") != ANCHOR_OK or (r3 or {}).get("subtopicCode") != ANCHOR_OK2:
        fail("anchor echo broken")

    # ── fail-closed attribution + validation + auth ──
    s4, _ = vote("rn_probe004", "helpful", ANCHOR_UNKNOWN)
    s5, _ = vote("rn_probe005", "helpful", SUBJECT_ROOT)
    s6, _ = vote("rn_probe006", "meh", ANCHOR_OK)
    s7, _ = http("POST", "/api/v1/learners/me/note-votes", payload={
        "noteId": "rn_anon", "vote": "helpful", "subtopicCode": ANCHOR_OK})
    print(f"3. negatives: unknown={s4} non_structure={s5} bad_vote={s6} anonymous={s7}")
    if s4 != 404:
        fail(f"unknown anchor expected 404, got {s4}")
    if s5 != 404:
        fail(f"non-structure anchor expected 404, got {s5}")
    if s6 != 400:
        fail(f"garbage vote expected 400, got {s6}")
    if s7 != 401:
        fail(f"anonymous expected 401, got {s7}")

    # ── state view ──
    s8, st = http("GET", "/api/v1/learners/me/state", token=tok)
    votes = (st or {}).get("noteVotes")
    print(f"4. state: {s8} noteVotes={len(votes) if votes is not None else 'MISSING'} "
          f"flashcardRatings={len((st or {}).get('flashcardRatings') or [])} (must be 0)")
    if s8 != 200 or not isinstance(votes, list):
        fail("state view missing noteVotes (deploy skew? core too old?)")
    if len(votes) != 3:
        fail(f"expected 3 vote events, got {len(votes)}")
    if votes[0]["noteId"] != "rn_probe003":
        fail(f"newest-first order broken: {votes[0]['noteId']} leads")
    if votes[-1]["noteId"] != "rn_probe001":
        fail(f"oldest-last broken: {votes[-1]['noteId']} trails")
    if (st or {}).get("flashcardRatings"):
        fail("vote traffic leaked into the ratings slice")

    # ── honesty pin: votes-only learner has NO mastery ──
    skills = (st or {}).get("skillStates")
    print(f"5. honesty pin: skillStates={len(skills) if skills is not None else 'MISSING'} (must be 0)")
    if skills != []:
        fail("SELF-REPORT LEAKED INTO MASTERY — skillStates not empty on a votes-only learner")

    # ── append semantics: a vote CHANGE is new evidence, latest wins ──
    s9, r9 = vote("rn_probe001", "not-helpful", ANCHOR_OK)
    time.sleep(0.3)
    s10, st2 = http("GET", "/api/v1/learners/me/state", token=tok)
    votes2 = (st2 or {}).get("noteVotes") or []
    print(f"6. vote change: {s9} events={len(votes2)} "
          f"current(rn_probe001)={next((x['vote'] for x in votes2 if x['noteId'] == 'rn_probe001'), 'NONE')}")
    if s9 != 201 or len(votes2) != 4:
        fail("append semantics broken")
    if next((x["vote"] for x in votes2 if x["noteId"] == "rn_probe001"), None) != "not-helpful":
        fail("latest event per note is not the current vote")

    print("ALL PINS GREEN — note votes evidence class verified on prod")


if __name__ == "__main__":
    main()
