#!/usr/bin/env python3
"""Cross-session/identity wording-variant sweep (READ-ONLY, API-level).
syllabai-core CI monitor (FULL MATRIX). Migrated verbatim from the sandbox
harness scripts/identity_variant_sweep.py on operator direction "move the
9-anchor matrix into the syllabai-core repo as a CI workflow (GitHub
Actions)" (trace 1a0e41994f6d6ac5). Authoritative runner:
.github/workflows/anchor-sweep.yml (schedule 20:30 UTC = 02:30 Asia/Dhaka
+ manual dispatch with optional SWEEP_BASE override); the sandbox daemon
remains as a second network vantage. Provenance: sweep 1a0df738373ddec9 ->
anchor swap 1a0e2ce15fc02b5d -> B2 pin 1a0e323dbeb8a161 -> B1 pin
1a0e3d5e56d0c801 -> C1/D1 full matrix 1a0e3e6722ee9855. The 09-27 sandbox
rollback proved why this needs a rollback-proof home.
Auth: operator 'scan for variations of other sessions/identities'
(trace 1a0df738373ddec9) + harness anchor swap (trace 1a0e2ce15fc02b5d,
"swap harness anchors to assert GUARD-REFUSAL for A1-shape asks") + B2
anchor promotion (trace 1a0e323dbeb8a161, "promote B2 to a hard
GUARD-REFUSAL anchor") + B1 pinned-verdict promotion (trace
1a0e3d5e56d0c801, "promote B1 to a pinned verdict") + C1/D1 pin
promotion (trace 1a0e3e6722ee9855, "pin C1/D1 to complete the matrix").

ROLLBACK NOTE (trace 1a0e3d5e56d0c801): sandbox state was rolled back to
the post-DATA-FIX-EXECUTED snapshot, wiping the anchor-swap and B2 edits
from this file and the matching worklog sections. This file was restored
byte-exact to the 6/6-green B2-HARD-ANCHOR state from the conversation
record, then the B1 promotion applied. Production (upstream-owned guard
deploy) was unaffected.

POST-GUARD ANCHORS (09-27 adjudication direction (c); direction (a) landed
UPSTREAM — sessions s138-s141, commits cfa9298 + b17214a "bank ambiguity does
not disarm the guard", deployed live e728b7d 09:56Z; the sandbox's parallel
implementation 1fc95c3 was discarded in favor of the upstream contract):
  A1/A2/A3 june-2019-P2-Q10 family  -> GUARD-REFUSAL  (hard anchor; the
      adjudicated fail-open shape: complete identity stated, zero validated
      anchors bound, June-2019-2C paper has only Q1-Q8 — must refuse, never
      serve the Jan-2022-1C bleed magnet under the wrong-paper label)
  B1 june-2019-P2-Q5                -> SERVED(cites)  (hard anchor since
      trace 1a0e3d5e56d0c801: guard-PRECISION sentinel, the complement of
      the refusal pins — validated June-2019-P2-Q5 anchors DO exist
      (data-fix 09-27), so the guard must NOT fire here. Observed
      SERVED(cites) across three runs incl. structured Q5 5(a)/(b)
      overview with bank-card lead cite d1bc1384 p.1. Pin asserts honest
      grounded serve (not refused, >=1 citation); does NOT pin doc-ids or
      answer text — secondary cites vary with groq retrieval)
  B2 june-2019-P1-Q3                -> GUARD-REFUSAL  (hard anchor since
      trace 1a0e323dbeb8a161: second adjudicated fail-open shape, same
      structural rule — stated identity binds zero validated June-2019-1C
      anchors; observed live refusing 09-27 pre-promotion. DATA-COUPLED:
      flips to SERVED when June-2019-1C QP/MS is ingested/linked — retire
      or flip this anchor deliberately at that ingest wave, never silently)
  C1 jan-2022-2CR-Q1                -> SERVED(cites)  (hard anchor since
      trace 1a0e3e6722ee9855: covered identity — data-fix 09-27 flipped
      the 2CR .md family VALIDATED, so the guard must NOT fire; observed
      SERVED(cites) across three runs pinning the real 2CR .md docs
      f640f7d9/80c28ff0. Retires the DATA-FIX-EXECUTED observe-only
      state; if the .md family is ever re-adjudicated, this anchor goes
      red BY DESIGN — retire/flip deliberately, never silently)
  D1 jan-2016-2C-Q2 (legacy 4CH0)   -> SERVED(cites)  (hard anchor since
      trace 1a0e3e6722ee9855: legitimate legacy serve — lead cite
      1e03ec0f is the RIGHT paper (4CH0-2C-2016jan QP.md); observed
      SERVED(cites) across three runs. Verdict-class pin is robust to
      future ingest waves: cite composition may shift, the class must not)
  E1/E2 CONTROL jan-2022-1C-Q4      -> FULL-PIN(1C)   (hard anchor; trio
      2d265267 card / 125b1f13 QP / 9c965f1e MS must stay byte-stable)

FULL MATRIX (trace 1a0e3e6722ee9855): all nine asks pinned — every sweep
run is a complete regression gate: 4 refusal pins (A1/A2/A3/B2) + 3
grounded-serve pins (B1/C1/D1) + 2 byte-stable controls (E1/E2).

PARAPHRASE EXPANSION (trace 1a0e45b9b32ede41, operator: "authorize the
H1 fix"): the deep-audit 2026-09-28 found the guard bypassable by
paraphrase — the parser's QNUM grammar matched only 'question|q' + ASCII
digits, so 'the tenth question of june 2019 paper 2' was classed
not-a-paper-ask and SERVED wrong-paper bleed with citations (live
evidence pre-fix: 6 cites incl. Jan-2022 QP chunks 125b1f13 + June-2019
Q2/Q7 MS excerpts for a nonexistent Q10). Fix landed upstream d133e41
(+ ae15cf4 test-contract correction), deployed and live-verified: the
paraphrase now GUARD-REFUSES. Anchors A4/A5 pin the widened grammar as
PARAPHRASE-DETECTION supports: they prove the widened parse reaches the
same guard, they do NOT define the coverage class (a green run is proof
about these strings, not about every possible phrasing). Matrix now
4+2 refusal / 3 grounded-serve / 2 controls = 11 asks.

GUARD-REFUSAL detection (upstream KaRagService contract): refused==true AND
provider=="deterministic-paper-refusal" (the echoed-identity deterministic
guard refusal — PAPER_IDENTITY_REFUSAL "I could not find question 10 from the
June 2019 paper 2..."), with the answer phrase as a secondary check. A refusal
via the generic grounding gate (provider "deterministic-refusal", no echo) is
HONEST-REFUSE and does NOT satisfy the A-anchors: the guard must be structural,
not pool-composition luck.
Governance reminder: refused=False is never trusted; content-level refusals
are visible in answer text only.
"""
import json, os, re, sys, time, urllib.request, urllib.error

BASE = os.environ.get("SWEEP_BASE", "https://syllabai-core.onrender.com")
TRIO = ("2d265267", "125b1f13", "9c965f1e")  # card / QP / MS doc-id prefixes
GUARD_PROVIDER = "deterministic-paper-refusal"  # upstream KaRagService marker
GUARD_PHRASE = "I could not find"               # echoed-identity secondary check

EXPECTED = {   # FULL MATRIX (trace 1a0e3e6722ee9855): all 9 asks pinned
    "A1 bank-Q10 drop-from":  "GUARD-REFUSAL",
    "A2 bank-Q10 bare":       "GUARD-REFUSAL",
    "A3 bank-Q10 alt-verb":   "GUARD-REFUSAL",
    "A4 bank-Q10 word-ordinal": "GUARD-REFUSAL",
    "A5 bank-Q10 number-interp": "GUARD-REFUSAL",
    "B1 bank-coverage P2-Q5": "SERVED(cites)",
    "B2 bank-coverage P1-Q3": "GUARD-REFUSAL",
    "C1 jan2022-2CR":         "SERVED(cites)",
    "D1 jan2016-2C legacy":   "SERVED(cites)",
    "E1 CONTROL known-good":  "FULL-PIN(1C)",
    "E2 CONTROL op-named":    "FULL-PIN(1C)",
}

RULES = {   # surfaced on drift: what a red means + the deliberate action required
    "A1 bank-Q10 drop-from":
        "STRUCTURAL (refusal side). Red = guard stopped refusing a zero-anchor "
        "identity (regression) or the adjudication itself changed. Never retire "
        "silently; adjudication changes need an operator decision record.",
    "A2 bank-Q10 bare": "Same rule as A1 (family anchor).",
    "A3 bank-Q10 alt-verb": "Same rule as A1 (family anchor).",
    "A4 bank-Q10 word-ordinal":
        "PARAPHRASE-DETECTION (code-coupled, refusal side). Red = the widened "
        "qnum grammar regressed (word-ordinals stopped parsing) or identity "
        "semantics changed deliberately. Fix the parser, or retire/flip with "
        "that deliberate change in the same wave. A green run proves THIS "
        "string parses and refuses — never the whole paraphrase class.",
    "A5 bank-Q10 number-interp":
        "PARAPHRASE-DETECTION (code-coupled, refusal side). Red = 'question "
        "number N' interposition stopped parsing (or deliberate grammar "
        "change). Fix the parser, or retire/flip deliberately in the same "
        "wave. Same class caveat as A4.",
    "B1 bank-coverage P2-Q5":
        "GUARD-PRECISION sentinel (serve side). Red = guard refused a covered "
        "identity (precision regression) or Q5 coverage was removed. If a data "
        "wave removed coverage: flip to GUARD-REFUSAL deliberately in the same "
        "change; otherwise treat as a production defect.",
    "B2 bank-coverage P1-Q3":
        "DATA-COUPLED. Expected flip: June-2019-1C QP/MS ingested/linked -> the "
        "guard legitimately stops refusing. If an ingest wave explains it: flip "
        "to SERVED(cites) deliberately in the same change. If NO ingest wave: "
        "pool bleed is back — production defect, investigate.",
    "C1 jan2022-2CR":
        "DATA-COUPLED. Expected flips: 2CR .md family re-adjudicated (-> "
        "refusal class) or cite loss (-> SERVED(no-cites)). Either way: "
        "data-side review first, then retire/flip deliberately in the same "
        "change. Never silently.",
    "D1 jan2016-2C legacy":
        "VERDICT-CLASS pin (robust to ingest waves: cite composition may shift, "
        "the class must not). Red = legacy 4CH0 serve began refusing or lost "
        "all citations. Verify legacy corpus validation state; treat as defect "
        "unless a deliberate de-validation wave explains it.",
    "E1 CONTROL known-good":
        "CONTROL (byte-stable trio). Red = production regression or "
        "content-export change — controls are NOT data-coupled, so a red is a "
        "defect until proven otherwise. Never retire silently.",
    "E2 CONTROL op-named": "Same rule as E1 (control pair).",
}


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


for i in range(12):
    s, _ = http("GET", "/actuator/health", timeout=30)
    if s == 200:
        print(f"warm try {i+1}"); break
    time.sleep(10)

uname = f"idsweep_{int(time.time())}"
s, reg = http("POST", "/api/v1/auth/register", payload={
    "email": f"{uname}@syllabai-test.dev", "password": "Probe-Learner-2026!",
    "displayName": "identity-sweep"})
ltok = (reg or {}).get("accessToken") or (reg or {}).get("token") if s in (200, 201) else None
if not ltok:
    s2, lg = http("POST", "/api/v1/auth/login", payload={
        "email": f"{uname}@syllabai-test.dev", "password": "Probe-Learner-2026!"})
    ltok = (lg or {}).get("accessToken") or (lg or {}).get("token") if s2 == 200 else None
print(f"learner ready: {bool(ltok)}")

ASKS = [
    ("A1 bank-Q10 drop-from",    "explain question 10 june 2019 paper 2"),
    ("A2 bank-Q10 bare",         "june 2019 paper 2 question 10"),
    ("A3 bank-Q10 alt-verb",     "how do i answer question 10 from june 2019 paper 2"),
    ("A4 bank-Q10 word-ordinal", "the tenth question of june 2019 paper 2"),
    ("A5 bank-Q10 number-interp", "question number 10 of june 2019 paper 2"),
    ("B1 bank-coverage P2-Q5",   "explain question 5 from june 2019 paper 2"),
    ("B2 bank-coverage P1-Q3",   "explain question 3 from june 2019 paper 1"),
    ("C1 jan2022-2CR",           "explain question 1 jan 2022 paper 2"),
    ("D1 jan2016-2C legacy",     "explain question 2 jan 2016 paper 2"),
    ("E1 CONTROL known-good",    "give me the answer of jan 2022 question 4 paper 1"),
    ("E2 CONTROL op-named",      "explain jan 2022 paper 1 question 4"),
]

summary = []
for label, q in ASKS:
    s, a = http("POST", "/api/v1/tutor/ask", token=ltok, payload={"question": q})
    print(f"\n== {label} == q={q!r}")
    if not isinstance(a, dict):
        print(f"   status={s} body={str(a)[:200]}")
        summary.append((label, "ERROR", None))
        continue
    refused = a.get("refused")
    cites = a.get("citations") or []
    doc_ids, pages = [], []
    for c in cites:
        m = re.search(r"/documents/([0-9a-f]{8})", str(c.get("deepLink", "")))
        if m:
            doc_ids.append(m.group(1))
            pages.append(str(c.get("page") or re.search(r"page=(\d+)", str(c.get("deepLink", ""))).group(1) if re.search(r"page=(\d+)", str(c.get("deepLink", ""))) else "?"))
    trio_present = all(t in doc_ids for t in TRIO)
    ans = (a.get("answer") or "")
    provider = str(a.get("provider") or "")
    guard_marked = bool(refused) and (provider == GUARD_PROVIDER
                                      or GUARD_PHRASE in ans)
    if guard_marked:
        verdict = "GUARD-REFUSAL"
    elif refused:
        verdict = "HONEST-REFUSE"
    elif trio_present:
        verdict = "FULL-PIN(1C)"
    elif doc_ids:
        verdict = "SERVED(cites)"
    else:
        verdict = "SERVED(no-cites)"
    expected = EXPECTED.get(label)
    anchor = f" expected={expected}" if expected else " (observe)"
    anchor_ok = (expected is None) or (verdict == expected)
    print(f"   status={s} refused={refused} guardMarked={guard_marked} provider={provider} "
          f"evidenceCount={a.get('evidenceCount')} latencyMs={a.get('latencyMs')} "
          f"verdict={verdict}{anchor}{'' if anchor_ok else '  << ANCHOR FAIL'}")
    print(f"   cite-docs: {list(dict.fromkeys(doc_ids))}")
    for idx, c in enumerate(cites, 1):
        print(f"     [{idx}] {json.dumps(c, sort_keys=True)[:170]}")
    print(f"   answer[:360]: {ans[:360]!r}")
    summary.append((label, verdict, expected if expected is not None else "-"))
    time.sleep(2)

print("\n==== SUMMARY ====")
fails = 0
for label, verdict, expected in summary:
    mark = ""
    if expected not in ("-", None):
        ok = verdict == expected
        mark = "PASS" if ok else "FAIL"
        fails += 0 if ok else 1
    print(f"  {verdict:<16} {label:<28} expected={expected:<12} {mark}")
print(f"ANCHORS: {len(EXPECTED) - fails}/{len(EXPECTED)} green")
drifted = [(label, verdict, expected) for label, verdict, expected in summary
           if expected not in ("-", None) and verdict != expected]
if drifted:
    print("\n==== DRIFT ANNOTATION (BY-DESIGN retire/flip rules) ====")
    for label, verdict, expected in drifted:
        print(f"\n  [{label}]  observed={verdict}  expected={expected}")
        print(f"    rule: {RULES.get(label, 'UNREGISTERED — register the rule in RULES before retiring or flipping anything.')}")
print("DONE")
sys.exit(1 if fails else 0)
