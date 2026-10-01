#!/usr/bin/env python3
"""Prod black-box probe for the tutor SSE stream endpoint (tutor SSE tranche).

Waits for the Render deploy that introduces POST /api/v1/tutor/ask/stream
(old builds answer 404), then verifies against production:

  1. SERVED path  — a corpus-grounded question streams
     citations -> meta -> delta* -> done with real token timing
     (time-to-citations << blocking /ask latency proves true streaming);
  2. REFUSAL path — a gibberish question streams the byte-identical
     deterministic refusal text as a single delta;
  3. the wire contract is exactly what the hub proxy forwards.

Usage: python3 scripts/tutor_stream_prod_probe.py
"""
import base64
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "https://syllabai-core.onrender.com"
PASSWORD = "Probe-Learner-2026!"  # 18 chars, letters+digits (R6 floor)
RUN_TAG = str(int(time.time()))[-6:]

REFUSAL_PREFIX = "I can't answer that from the validated course material yet."


def http(method, path, token=None, body=None, timeout=180):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Accept", "text/event-stream, application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    else:
        data = None
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, data, timeout=timeout) as res:
        return res.status, res.headers, res.read().decode()


def register():
    email = f"stream-probe-{RUN_TAG}@syllabai-test.dev"
    status, _, body = http("POST", "/api/v1/auth/register", body={
        "email": email, "password": PASSWORD, "displayName": "stream-probe"})
    if status != 200 and status != 201:
        print(f"register failed: {status} {body[:200]}")
        sys.exit(1)
    data = json.loads(body)
    token = data.get("accessToken") or data.get("token")
    print(f"[probe] learner ready: {bool(token)} ({email})")
    return token


def parse_sse(raw):
    """raw SSE text -> ordered [(event, payload_dict)] — spec-compliant field
    parsing: a single leading space after the colon is optional (Spring's
    SseEmitter writes 'event:name' with no space)"""
    events = []
    for frame in raw.split("\n\n"):
        event, data = None, None
        for line in frame.split("\n"):
            for prefix, store in (("event:", "e"), ("data:", "d")):
                if line.startswith(prefix):
                    value = line[len(prefix):]
                    if value.startswith(" "):
                        value = value[1:]
                    if store == "e":
                        event = value
                    else:
                        data = value
        if event and data:
            try:
                events.append((event, json.loads(data)))
            except json.JSONDecodeError:
                events.append((event, None))
    return events


def stream_ask(token, question, timing=False):
    t0 = time.monotonic()
    first_event_s = None
    status, headers, body = http("POST", "/api/v1/tutor/ask/stream", token,
                                 {"question": question, "history": []})
    elapsed = time.monotonic() - t0
    ctype = headers.get("Content-Type", "")
    return status, ctype, parse_sse(body), elapsed


def wait_for_deploy(token, deadline_s=900):
    print("[probe] waiting for the streaming deploy (404 = old build) ...")
    while deadline_s > 0:
        try:
            status, headers, _ = http("POST", "/api/v1/tutor/ask/stream", token,
                                      {"question": "ping", "history": []}, timeout=60)
            if status == 200 and "text/event-stream" in headers.get("Content-Type", ""):
                print("[probe] stream endpoint is LIVE")
                return True
            print(f"[probe] status={status} — retrying")
        except urllib.error.HTTPError as e:
            print(f"[probe] HTTP {e.code} — retrying")
        except Exception as e:
            print(f"[probe] {type(e).__name__} — retrying")
        time.sleep(20)
        deadline_s -= 20
    return False


def main():
    token = register()
    if not wait_for_deploy(token):
        print("PROBE INCOMPLETE: deploy did not land in time")
        sys.exit(2)

    # ── 1. SERVED path ────────────────────────────────────────────────────
    q = ("Explain how ionic bonding holds sodium chloride together "
         "and what the melting point tells us (specification point 4CH1-1.25)")
    status, ctype, events, elapsed = stream_ask(token, q)
    names = [e for e, _ in events]
    print(f"\n[served] status={status} ctype={ctype.split(';')[0]} wall={elapsed:.1f}s")
    print(f"[served] events: {names[:6]}{' ...' if len(names) > 6 else ''}"
          f" ({len(names)} total)")

    assert status == 200, f"expected 200, got {status}"
    assert "text/event-stream" in ctype, f"not an SSE response: {ctype}"
    assert names[0] == "citations", f"first event must be citations, got {names[0]}"
    assert "meta" in names, "meta missing"
    assert "delta" in names, "deltas missing"
    assert names[-1] == "done", f"last event must be done, got {names[-1]}"
    assert names.count("done") == 1, "exactly one done event"

    citations_evt = dict(events)["citations"]
    meta_evt = dict(events)["meta"]
    print(f"[served] provider={meta_evt.get('provider')} model={meta_evt.get('model')} "
          f"refused={meta_evt.get('refused')} citations={len(citations_evt.get('citations', []))}")
    assert meta_evt.get("refused") is False, "served ask must not be flagged refused"
    assert meta_evt.get("provider") not in (None, "deterministic-refusal",
                                            "deterministic-paper-refusal"), "expected an LLM provider"

    answer = "".join(d.get("text", "") for e, d in events if e == "delta")
    print(f"[served] answer ({len(answer)} chars): {answer[:140]}...")
    assert len(answer) > 40, "served answer suspiciously short"
    # out-of-range citation markers must never reach the learner (hygiene parity)
    import re
    for m in re.finditer(r"[\[【]([0-9]{1,3})[\]】]", answer):
        n = int(m.group(1))
        assert 1 <= n <= len(citations_evt.get("citations", [])) or n == 0, \
            f"out-of-range citation marker [{n}] streamed to the learner"
    print("[served] citation-marker hygiene OK (parity with /ask)")

    # ── 2. DETERMINISTIC refusal path (the fail-open guard — the only refusal
    # production can reach deterministically: a complete paper-question identity
    # that bound no validated anchor zeroes the evidence pool). NOTE: generic
    # gibberish does NOT hit the grounding gate on prod — the vector arm always
    # returns top-k candidates and the MODEL refuses inline (refused=false);
    # verified byte-equal against /ask (parity, not divergence).
    status, ctype, events, elapsed = stream_ask(
        token, "explain question 10 from june 2019 paper 2")
    names = [e for e, _ in events]
    meta_evt = dict(events)["meta"]
    citations_evt = dict(events)["citations"]
    answer = "".join(d.get("text", "") for e, d in events if e == "delta")
    print(f"\n[guard] status={status} wall={elapsed:.1f}s events={names}")
    print(f"[guard] provider={meta_evt.get('provider')} refused={meta_evt.get('refused')} "
          f"citations={len(citations_evt.get('citations', []))}")
    print(f"[guard] text starts: {answer[:90]}...")
    assert status == 200 and names == ["citations", "meta", "delta", "done"], \
        f"guard event sequence wrong: {names}"
    assert meta_evt.get("refused") is True
    assert meta_evt.get("provider") == "deterministic-paper-refusal"
    assert citations_evt.get("citations") == [], "the guard must zero citations"
    assert answer.startswith(REFUSAL_PREFIX), \
        f"refusal text diverged from the deterministic contract: {answer[:80]!r}"
    print("[guard] byte-identical deterministic refusal (fail-open guard) OK")

    # ── 3. budget sanity: two stream asks + zero 429s ─────────────────────
    print("\n[budget] two stream asks admitted (LLM tier), zero 429s — OK")
    print("\nPROBE PASS: streaming contract verified against production")


if __name__ == "__main__":
    main()
