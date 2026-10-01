#!/usr/bin/env python3
"""
flashcard_ratings_prod_probe.py — black-box verification of the flashcard
rating evidence class on PROD (V47, ADR-029 tranche 4.4, trace 1a0e8ebb1f436dbd).

Pins, in order:
  1. fresh probe learner registers against the live R6 floor;
  2. happy path: three ratings on real pilot deck anchors record (201);
  3. fail-closed attribution: unknown anchor -> 404, non-SUBTOPIC anchor
     (the subject root code) -> 404, garbage rating -> 400, anonymous -> 401;
  4. /state carries flashcardRatings (3, newest first, hub wire vocabulary);
  5. THE HONESTY PIN, black-box: a ratings-only learner has skillStates == []
     — self-report never produces mastery;
  6. append semantics: re-rating the same card appends (4 events total).

Exit 0 = all pins green; nonzero = a pin failed (the failing line is printed).
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "https://syllabai-core.onrender.com"

SUBTOPIC_OK = "4CH1-S1-a"      # real pilot deck anchor (States of matter)
SUBTOPIC_OK2 = "4CH1-S1-b"     # Elements, compounds and mixtures
SUBJECT_ROOT = "4CH1"          # a real node that is NOT a subtopic
SUBTOPIC_UNKNOWN = "4CH1-S99-z"


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

    uname = f"fcrprobe_{int(time.time())}"
    s, reg = http("POST", "/api/v1/auth/register", payload={
        "email": f"{uname}@syllabai-test.dev", "password": "Probe-Learner-2026!",
        "displayName": "flashcard-probe"})
    tok = (reg or {}).get("accessToken") if s in (200, 201) else None
    print(f"1. register: {s} learner_ready={bool(tok)}")
    if not tok:
        fail("register failed")

    def rate(card, rating, anchor, t=tok):
        return http("POST", "/api/v1/learners/me/flashcard-ratings", token=t, payload={
            "cardId": card, "rating": rating, "subtopicCode": anchor})

    # ── happy path ──
    s1, r1 = rate("fl_probe001", "know", SUBTOPIC_OK)
    s2, r2 = rate("fl_probe002", "still-learning", SUBTOPIC_OK)
    s3, r3 = rate("fl_probe003", "know", SUBTOPIC_OK2)
    print(f"2. ratings: [{s1},{s2},{s3}] "
          f"({(r1 or {}).get('rating')},{(r2 or {}).get('rating')},{(r3 or {}).get('rating')}) "
          f"anchors=({(r1 or {}).get('subtopicCode')},{(r3 or {}).get('subtopicCode')})")
    if (s1, s2, s3) != (201, 201, 201):
        fail("happy-path ratings did not 201")
    if (r1 or {}).get("rating") != "know" or (r2 or {}).get("rating") != "still-learning":
        fail("rating vocabulary round-trip broken")
    if (r1 or {}).get("subtopicCode") != SUBTOPIC_OK or (r3 or {}).get("subtopicCode") != SUBTOPIC_OK2:
        fail("anchor echo broken")

    # ── fail-closed attribution + validation + auth ──
    s4, _ = rate("fl_probe004", "know", SUBTOPIC_UNKNOWN)
    s5, _ = rate("fl_probe005", "know", SUBJECT_ROOT)
    s6, _ = rate("fl_probe006", "banana", SUBTOPIC_OK)
    s7, _ = http("POST", "/api/v1/learners/me/flashcard-ratings", payload={
        "cardId": "fl_anon", "rating": "know", "subtopicCode": SUBTOPIC_OK})
    print(f"3. negatives: unknown={s4} non_subtopic={s5} bad_rating={s6} anonymous={s7}")
    if s4 != 404:
        fail(f"unknown anchor expected 404, got {s4}")
    if s5 != 404:
        fail(f"non-subtopic anchor expected 404, got {s5}")
    if s6 != 400:
        fail(f"garbage rating expected 400, got {s6}")
    if s7 != 401:
        fail(f"anonymous expected 401, got {s7}")

    # ── state view ──
    s8, st = http("GET", "/api/v1/learners/me/state", token=tok)
    ratings = (st or {}).get("flashcardRatings")
    print(f"4. state: {s8} flashcardRatings={len(ratings) if ratings is not None else 'MISSING'}")
    if s8 != 200 or not isinstance(ratings, list):
        fail("state view missing flashcardRatings (deploy skew? core too old?)")
    if len(ratings) != 3:
        fail(f"expected 3 rating events, got {len(ratings)}")
    if ratings[0]["cardId"] != "fl_probe003":
        fail(f"newest-first order broken: {ratings[0]['cardId']} leads")
    if ratings[-1]["cardId"] != "fl_probe001":
        fail(f"oldest-last broken: {ratings[-1]['cardId']} trails")

    # ── honesty pin: ratings-only learner has NO mastery ──
    skills = (st or {}).get("skillStates")
    print(f"5. honesty pin: skillStates={len(skills) if skills is not None else 'MISSING'} (must be 0)")
    if skills != []:
        fail("SELF-REPORT LEAKED INTO MASTERY — skillStates not empty on a ratings-only learner")

    # ── append semantics ──
    s9, r9 = rate("fl_probe001", "still-learning", SUBTOPIC_OK)
    s10, st2 = http("GET", "/api/v1/learners/me/state", token=tok)
    ratings2 = (st2 or {}).get("flashcardRatings") or []
    print(f"6. re-rate: {s9} events={len(ratings2)} "
          f"current(fl_probe001)={next((x['rating'] for x in ratings2 if x['cardId'] == 'fl_probe001'), 'NONE')}")
    if s9 != 201 or len(ratings2) != 4:
        fail("append semantics broken")
    if next((x["rating"] for x in ratings2 if x["cardId"] == "fl_probe001"), None) != "still-learning":
        fail("latest event per card is not the current rating")

    print("ALL PINS GREEN — flashcard ratings evidence class verified on prod")


if __name__ == "__main__":
    main()
