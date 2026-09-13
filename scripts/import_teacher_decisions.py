#!/usr/bin/env python3
"""
import_teacher_decisions.py — GATED importer for the staged teacher-validation
decision log (T-C04 R3-5).

Boundary (R3-5 directive + AGENT.md):
  * Applies REVIEW/SERVING-STATE transitions ONLY. Ingestion evidence
    (glm_ocr_bridge_records, documents, document_chunks, drafts,
    reconciliation) is NEVER read-writable here — reconciliation_status and
    REVIEW_REQUIRED conflicts are preserved verbatim.
  * Only validation_state on exam_papers / question_versions / mark_schemes
    may change, and ONLY with an audit row in teacher_validation_events (V18).
  * KG untouched: no knowledge_nodes/knowledge_edges writes (T-C11 promotion
    is out of scope and structurally impossible from this tool).
  * Machine gates only: identity preflight, strict chain verification,
    lifecycle checks against LIVE canonical state, idempotent replay
    (UNIQUE decision_seq+decision_hash), single transaction, post-apply
    invariant proof. No manual approvals.

Mapping (canonical schema has no FLAGGED state — V18 records flags without
changing serving state):
  VALIDATE -> validation_state=VALIDATED       (servable)
  REJECT   -> validation_state=REJECTED
  FLAG     -> events-only, state stays SUGGESTED
  REVERSE  -> restores SUGGESTED (or clears a flag); prior decision must be
              the last applied event on that target.

Hardening (audit findings F-1/F-2/F-3, 2026-09-14):
  * F-1 — the strict reader validates every entry BEFORE interpolation:
    targetId must parse as a UUID; hash/prevHash must be 64 lowercase hex
    chars. The hash chain proves log integrity, not content safety — a
    signed entry still reaches SQL text, so the interpolatable fields are
    validated independently of the chain.
  * F-2 — state-changing UPDATEs are conditional on the exact state plan()
    gated against (expect_state) and asserted in-transaction: a decision
    landing on the same target between plan and apply aborts the whole
    transaction instead of being silently overwritten.
  * F-3 — REVERSE accepts an optional structured `reverses` field
    (integer seq of the prior decision). The field is deliberately OUTSIDE
    the entry hash (that base is frozen to match the workbench writer;
    changing it would invalidate every recorded chain), so plan()
    re-validates the referenced prior entry against live state — tampering
    with it can only redirect a REVERSE to another currently eligible
    prior decision, still fail-closed and fully audited. Legacy logs keep
    the prose fallback (first integer in the note).

Usage:
  import_teacher_decisions.py check  [--log-file PATH]
  import_teacher_decisions.py apply  [--log-file PATH]
Exit 0 = green plan/apply; 1 = gate failure (fail-closed, nothing written).
"""
import hashlib
import json
import os
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, "/home/z/my-project/scripts")
from campaign_db_preflight import preflight  # fail-closed identity gate

PSQL = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"
PSQL_ARGS = ["-h", "/home/z/toolchain", "-U", "syllabai", "-d", "syllabai"]
PGDUMP = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/pg_dump"
LOG_FILE = Path("/home/z/my-project/download/teacher-validation/decision-log.jsonl")
RUNS_DIR = Path("/home/z/my-project/download/teacher-validation/runs")
GENESIS = "0" * 64

# Tables the importer may EVER write (validation state) + its own audit table.
WHITELIST = {"exam_papers", "question_versions", "mark_schemes",
             "teacher_validation_events"}
# Every content/evidence table that must remain BYTE-IDENTICAL across a run.
EVIDENCE_TABLES = [
    "questions", "question_parts", "question_options", "question_topics",
    "mark_points", "documents", "document_chunks", "glm_ocr_bridge_records",
    "knowledge_nodes", "knowledge_edges", "curriculum_versions", "subjects",
    "attempts", "answers", "smart_mark_results", "campaign_db_identity",
]

TARGET_TABLE = {"question_version": "question_versions",
                "mark_scheme": "mark_schemes",
                "exam_paper": "exam_papers"}


def q(sql):
    r = subprocess.run([PSQL] + PSQL_ARGS + ["-tA", "-c", sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed: {r.stderr.strip()}")
    return r.stdout.strip()


def table_sha(table):
    """Deterministic data dump hash of one table (isolation-script semantics)."""
    dump = subprocess.run(
        [PGDUMP, "-a", "--inserts", "-O", "-x", "-t", table] + PSQL_ARGS,
        capture_output=True, text=True)
    if dump.returncode != 0:
        raise RuntimeError(f"pg_dump failed for {table}: {dump.stderr[:200]}")
    lines = [l for l in dump.stdout.splitlines()
             if not l.startswith("\\restrict") and not l.startswith("\\unrestrict")]
    payload = "\n".join(lines)
    payload = payload[:-1] if payload.endswith("\n") else payload
    return hashlib.sha256(payload.encode()).hexdigest()


def validate_entry_fields(e, where):
    """F-1 hardening: validate the fields that would later be interpolated
    into SQL, at every stop between the log and SQL text. Chain membership
    proves the entry was not tampered with after signing — it says nothing
    about the signed content itself. Shared by read_log() and plan()."""
    try:
        uuid.UUID(e["targetId"])
    except (ValueError, TypeError, AttributeError):
        raise RuntimeError(
            f"{where} targetId is not a valid UUID: {e['targetId']!r}")
    for k in ("hash", "prevHash"):
        v = e[k]
        if (not isinstance(v, str) or len(v) != 64
                or any(c not in "0123456789abcdef" for c in v)):
            raise RuntimeError(
                f"{where} {k} is not 64 lowercase hex chars")
    if e.get("reverses") is not None and (
            not isinstance(e["reverses"], int)
            or isinstance(e["reverses"], bool)):
        raise RuntimeError(
            f"{where} field reverses must be an integer, "
            f"got {type(e['reverses']).__name__}")


def read_log(path: Path):
    """STRICT reader — a torn or malformed line aborts (importer is not a
    best-effort reader; the workbench log is fsync'd appends only)."""
    entries = []
    if not path.exists():
        return entries
    for ln, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError as ex:
            raise RuntimeError(f"chain error: malformed JSON at line {ln}: {ex}")
        for k in ("seq", "ts", "action", "targetType", "targetId",
                  "targetLabel", "reviewer", "note", "prevHash", "hash"):
            if k not in e:
                raise RuntimeError(f"chain error: line {ln} missing field {k}")
        validate_entry_fields(e, f"chain error: line {ln}")
        entries.append(e)
    return entries


def entry_hash(e):
    """Hash semantics identical to the workbench writer (TS JSON.stringify of
    the entry without `hash`, insertion key order preserved)."""
    base = {k: e[k] for k in ("action", "targetType", "targetId", "targetLabel",
                              "reviewer", "note", "seq", "ts", "prevHash")}
    return hashlib.sha256(
        json.dumps(base, separators=(",", ":"), ensure_ascii=False).encode()
    ).hexdigest()


def verify_chain(entries):
    prev = GENESIS
    for i, e in enumerate(entries, 1):
        if e["prevHash"] != prev:
            return False, f"link break at seq {e.get('seq')}: prevHash != previous hash"
        if e["seq"] != i:
            return False, f"seq discontinuity: expected {i}, found {e.get('seq')}"
        if entry_hash(e) != e["hash"]:
            return False, f"hash mismatch at seq {e.get('seq')} (entry tampered)"
        prev = e["hash"]
    return True, "chain valid"


def replay_effective(entries):
    """Same semantics as the workbench StageState replay."""
    effective = {}
    rev_targets = set()
    for e in entries:
        a = e["action"]
        if a in ("VALIDATE", "REJECT"):
            effective[e["targetId"]] = "VALIDATED" if a == "VALIDATE" else "REJECTED"
        elif a == "FLAG":
            effective.setdefault(e["targetId"], "FLAGGED")
        elif a == "REVERSE":
            rev_targets.add(e["targetId"])
            effective.pop(e["targetId"], None)
    return effective, rev_targets


def canonical_state(table, tid):
    v = q(f"SELECT validation_state FROM {table} WHERE id = '{tid}'")
    return v or None


def applied_decisions():
    rows = q("SELECT decision_seq, decision_hash FROM teacher_validation_events")
    out = set()
    for row in rows.splitlines():
        if row.strip():
            s, h = row.split("|")
            out.add((int(s), h))
    return out


def last_event_state(table, tid):
    r = q(f"""SELECT result_state FROM teacher_validation_events
              WHERE target_type = (
                  CASE '{table}' WHEN 'question_versions' THEN 'question_version'
                                 WHEN 'mark_schemes' THEN 'mark_scheme'
                                 ELSE 'exam_paper' END)
              AND target_id = '{tid}'
              ORDER BY id DESC LIMIT 1""")
    return r or None


def plan(entries, mode):
    """Build the operation list; raises RuntimeError on any gate violation."""
    chain_ok, chain_msg = verify_chain(entries)
    if not chain_ok:
        raise RuntimeError(f"CHAIN VERIFY FAILED ({chain_msg}) — refusing to operate")
    applied = applied_decisions()
    ops, dup, events_only = [], 0, 0
    seen = {}
    for e in entries:
        seq, h, action = e["seq"], e["hash"], e["action"]
        if (seq, h) in applied:
            dup += 1
            continue
        # F-1 defense-in-depth: read_log() validates every entry, but plan()
        # is the last stop before SQL text — never interpolate an id that was
        # not validated on this path too.
        validate_entry_fields(e, f"gate: seq {seq}")
        table = TARGET_TABLE[e["targetType"]]
        tid = e["targetId"]
        if action in ("VALIDATE", "REJECT", "FLAG"):
            cur = canonical_state(table, tid)
            if cur is None:
                raise RuntimeError(
                    f"gate: seq {seq} targets unknown {e['targetType']}:{tid} "
                    "(nonexistent or quarantined content) — aborting")
            if cur != "SUGGESTED":
                raise RuntimeError(
                    f"gate: seq {seq} targets {e['targetType']}:{tid} in state "
                    f"{cur}, not SUGGESTED (seed lock / lifecycle violation) — aborting")
            if action == "VALIDATE":
                ops.append({"seq": seq, "hash": h, "table": table, "tid": tid,
                            "set_state": "VALIDATED", "result_state": "VALIDATED",
                            "expect_state": cur})
            elif action == "REJECT":
                ops.append({"seq": seq, "hash": h, "table": table, "tid": tid,
                            "set_state": "REJECTED", "result_state": "REJECTED",
                            "expect_state": cur})
            else:  # FLAG — annotation only, serving state unchanged
                events_only += 1
                ops.append({"seq": seq, "hash": h, "table": table, "tid": tid,
                            "set_state": None, "result_state": "SUGGESTED"})
        elif action == "REVERSE":
            # F-3: structured `reverses` field first; the legacy prose
            # fallback (first integer in the note) keeps pre-field logs —
            # including the durable tranche-1 export — replayable.
            prior_seq = e.get("reverses")
            if prior_seq is None:
                for tok in e["note"].split():
                    if tok.isdigit():
                        prior_seq = int(tok)
                        break
            if prior_seq is None:
                raise RuntimeError(
                    f"gate: seq {seq} REVERSE carries no reverses field and "
                    "its note names no prior seq — aborting")
            prior = next((x for x in entries if x["seq"] == prior_seq), None)
            if prior is None or prior["action"] not in ("VALIDATE", "REJECT", "FLAG"):
                raise RuntimeError(
                    f"gate: seq {seq} REVERSE references unusable prior seq {prior_seq} — aborting")
            ptable = TARGET_TABLE[prior["targetType"]]
            ptid = prior["targetId"]
            cur = canonical_state(ptable, ptid)
            last = last_event_state(ptable, ptid)
            if prior["action"] == "FLAG":
                ops.append({"seq": seq, "hash": h, "table": ptable, "tid": ptid,
                            "set_state": None, "result_state": "SUGGESTED",
                            "reverses": prior_seq})
                events_only += 1
                continue
            expected = "VALIDATED" if prior["action"] == "VALIDATE" else "REJECTED"
            if cur != expected or last != expected:
                raise RuntimeError(
                    f"gate: seq {seq} REVERSE of seq {prior_seq}: target state "
                    f"{cur}/last-event {last} != {expected} — state moved on since; refusing")
            ops.append({"seq": seq, "hash": h, "table": ptable, "tid": ptid,
                        "set_state": "SUGGESTED", "result_state": "SUGGESTED",
                        "expect_state": expected, "reverses": prior_seq})
    return ops, dup, events_only, chain_ok, chain_msg


def sql_lit(s):
    """SQL single-quoted literal (standard_conforming_strings=on: doubling
    quotes is sufficient; backslashes stay literal)."""
    return "'" + str(s).replace("'", "''") + "'"


def apply_ops(ops, entries, log_sha, mode):
    run_id = str(uuid.uuid4())
    # snapshot pre-state for the manifest
    pre_states = {t: q(f"SELECT count(*) FROM {t}") for t in TARGET_TABLE.values()}
    pre_counts = {t: q(f"SELECT count(*) FROM {t}")
                  for t in ["exam_papers", "question_versions", "mark_schemes",
                            "glm_ocr_bridge_records", "documents",
                            "document_chunks", "knowledge_nodes", "knowledge_edges"]}
    pre_embedded = q("SELECT count(*) FROM document_chunks WHERE embedding IS NOT NULL")
    pre_review = q("SELECT count(*) FROM glm_ocr_bridge_records WHERE reconciliation_status='REVIEW_REQUIRED'")
    # byte-identity fingerprints OUTSIDE the whitelist
    pre_sha = {t: table_sha(t) for t in EVIDENCE_TABLES}

    # transaction: single atomic apply
    stmts = ["BEGIN"]
    for o in ops:
        entry = next(x for x in entries if x["seq"] == o["seq"])
        if o["set_state"] is not None:
            # F-2: conditional update + in-transaction guard. plan() gated
            # against live state, but state can move on between plan and
            # apply; the UNIQUE(seq,hash) guard only stops double-apply of
            # the SAME decision, not a DIFFERENT decision on the same target.
            stmts.append(
                f"UPDATE {o['table']} SET validation_state='{o['set_state']}' "
                f"WHERE id='{o['tid']}' "
                f"AND validation_state='{o['expect_state']}'")
            stmts.append(
                f"DO $guard$ BEGIN "
                f"IF (SELECT validation_state FROM {o['table']} "
                f"WHERE id='{o['tid']}') IS DISTINCT FROM '{o['set_state']}' "
                f"THEN RAISE EXCEPTION 'TOCTOU guard (seq {o['seq']}): "
                f"{o['table']}.{o['tid']} not in expected state "
                f"{o['expect_state']} at apply time — state moved on since "
                f"plan'; END IF; END $guard$")
        stmts.append(
            "INSERT INTO teacher_validation_events (importer_run_id, decision_seq, "
            "decision_hash, action, target_type, target_id, target_label, reviewer, "
            "note, result_state) VALUES ("
            f"'{run_id}', {o['seq']}, '{o['hash']}', '{entry['action']}', "
            f"'{entry['targetType']}', '{o['tid']}', "
            f"{sql_lit(entry['targetLabel'])}, "
            f"{sql_lit(entry['reviewer'])}, "
            f"{sql_lit(entry['note'])}, "
            f"'{o['result_state']}')")
    stmts.append("COMMIT")
    script = ";\n".join(stmts) + ";\n"
    r = subprocess.run([PSQL] + PSQL_ARGS + ["-v", "ON_ERROR_STOP=1", "-q"],
                       input=script, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"apply transaction failed (rolled back): {r.stderr.strip()[:400]}")

    # ---- post-apply invariants ----
    post_counts = {t: q(f"SELECT count(*) FROM {t}")
                   for t in ["exam_papers", "question_versions", "mark_schemes",
                             "glm_ocr_bridge_records", "documents",
                             "document_chunks", "knowledge_nodes", "knowledge_edges"]}
    post_embedded = q("SELECT count(*) FROM document_chunks WHERE embedding IS NOT NULL")
    post_review = q("SELECT count(*) FROM glm_ocr_bridge_records WHERE reconciliation_status='REVIEW_REQUIRED'")
    post_sha = {t: table_sha(t) for t in EVIDENCE_TABLES}
    evidence_byte_identical = {t: (pre_sha[t] == post_sha[t]) for t in EVIDENCE_TABLES}
    if not all(evidence_byte_identical.values()):
        bad = [t for t, ok in evidence_byte_identical.items() if not ok]
        raise RuntimeError(f"INVARIANT FAILURE: evidence tables mutated: {bad}")
    if pre_counts != post_counts:
        raise RuntimeError(f"INVARIANT FAILURE: row-count drift {pre_counts} -> {post_counts}")
    if post_embedded != "0":
        raise RuntimeError(f"INVARIANT FAILURE: embedded {post_embedded} != 0")
    if pre_review != post_review:
        raise RuntimeError("INVARIANT FAILURE: REVIEW_REQUIRED conflicts changed")
    # lifecycle consistency: last event per touched target == current state
    for o in ops:
        cur = canonical_state(o["table"], o["tid"])
        if cur != o["result_state"] and o["set_state"] is not None:
            raise RuntimeError(
                f"lifecycle mismatch on {o['table']}:{o['tid']}: state {cur} != {o['result_state']}")
    return {
        "importer_run_id": run_id,
        "pre_counts": pre_counts, "post_counts": post_counts,
        "embedded": post_embedded, "review_required_preserved": post_review,
        "evidence_byte_identical": evidence_byte_identical,
        "kg_untouched": {"knowledge_nodes": post_counts["knowledge_nodes"],
                         "knowledge_edges": post_counts["knowledge_edges"]},
        "states": {t: pre_states[t] for t in pre_states},
    }


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    if mode not in ("check", "apply"):
        print("usage: import_teacher_decisions.py check|apply [--log-file PATH]")
        sys.exit(1)
    log_path = LOG_FILE
    if "--log-file" in sys.argv:
        log_path = Path(sys.argv[sys.argv.index("--log-file") + 1])

    gate = preflight(expected_db="syllabai", expected_label="T-C04-CAMPAIGN")
    entries = read_log(log_path)
    log_sha = hashlib.sha256(log_path.read_bytes()).hexdigest() if log_path.exists() else GENESIS
    try:
        ops, dup, events_only, chain_ok, chain_msg = plan(entries, mode)
    except RuntimeError as ex:
        manifest = {
            "mode": mode, "ts": datetime.now(timezone.utc).isoformat(),
            "log_file": str(log_path), "log_sha256": log_sha,
            "entries": len(entries), "chain": "FAILED", "reason": str(ex),
            "decision": "ABORT — nothing written (fail-closed)",
        }
        RUNS_DIR.mkdir(parents=True, exist_ok=True)
        out = RUNS_DIR / f"importer-{mode}-ABORT-{datetime.now(timezone.utc).strftime('%H%M%S%f')}.json"
        out.write_text(json.dumps(manifest, indent=1))
        print(f"GATE FAILURE: {ex}")
        print(f"manifest: {out}")
        sys.exit(1)

    print(f"chain: {chain_msg} ({len(entries)} entries, head {entries[-1]['hash'][:12] if entries else GENESIS[:12]})")
    print(f"plan: {len(ops)} operation(s) [{dup} already-applied DUPLICATE skipped; "
          f"{events_only} events-only (FLAG)]")
    for o in ops:
        act = next(x for x in entries if x["seq"] == o["seq"])
        extra = f" -> {o['set_state']}" if o["set_state"] else " (annotation only)"
        rev = f" [reverses seq {o['reverses']}]" if o.get("reverses") else ""
        print(f"  seq {o['seq']:>3} {act['action']:<8} {o['table']}.{o['tid'][:8]}..{extra}{rev}")

    result = None
    if mode == "apply":
        if not ops:
            print("nothing to apply (all entries already applied) — idempotent no-op")
        else:
            try:
                result = apply_ops(ops, entries, log_sha, mode)
            except RuntimeError as ex:
                # apply-time failure (incl. the F-2 TOCTOU guard): the
                # transaction is already rolled back server-side; record a
                # graceful ABORT manifest like the plan gates do.
                RUNS_DIR.mkdir(parents=True, exist_ok=True)
                out = RUNS_DIR / (f"importer-apply-ABORT-"
                                  f"{datetime.now(timezone.utc).strftime('%H%M%S%f')}.json")
                out.write_text(json.dumps({
                    "mode": "apply",
                    "ts": datetime.now(timezone.utc).isoformat(),
                    "log_file": str(log_path), "log_sha256": log_sha,
                    "entries": len(entries), "reason": str(ex),
                    "decision": "ABORT — apply failed; transaction rolled back, nothing written",
                }, indent=1))
                print(f"APPLY FAILED (transaction rolled back, nothing written): {ex}")
                print(f"manifest: {out}")
                sys.exit(1)
            print(f"applied run {result['importer_run_id']}: "
                  f"{len(ops)} op(s), evidence tables byte-identical: "
                  f"{all(result['evidence_byte_identical'].values())}, "
                  f"embedded=0 ok, REVIEW_REQUIRED preserved "
                  f"({result['review_required_preserved']}), KG untouched "
                  f"(nodes {result['kg_untouched']['knowledge_nodes']}, edges {result['kg_untouched']['knowledge_edges']})")

    manifest = {
        "mode": mode,
        "ts": datetime.now(timezone.utc).isoformat(),
        "log_file": str(log_path),
        "log_sha256": log_sha,
        "entries": len(entries),
        "chain": chain_msg,
        "chain_head": entries[-1]["hash"] if entries else GENESIS,
        "duplicates_skipped": dup,
        "events_only_ops": events_only,
        "operations": ops,
        "identity": {"campaign": "T-C04-CAMPAIGN", "db": "syllabai"},
        "write_whitelist": sorted(WHITELIST),
        "evidence_tables_verified_untouched": EVIDENCE_TABLES if mode == "apply" else None,
        "apply_result": result,
        "decision": "APPLIED" if mode == "apply" else "CHECK-ONLY (nothing written)",
    }
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    out = RUNS_DIR / (f"importer-run-{result['importer_run_id'][:8] if mode == 'apply' and result else 'check'}-"
                      f"{datetime.now(timezone.utc).strftime('%H%M%S')}.json")
    out.write_text(json.dumps(manifest, indent=1))
    print(f"manifest: {out}")
    sys.exit(0)


if __name__ == "__main__":
    main()
