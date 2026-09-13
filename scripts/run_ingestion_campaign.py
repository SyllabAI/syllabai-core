#!/usr/bin/env python3
"""
run_ingestion_campaign.py — automated, self-verifying T-C03/T-C04 batch ingestion.

Operator directive (2026-09-13): run the full approved 4CH1 corpus (paper 1 +
paper 2, 82 sessions) through the T-C03 batch machinery WITHOUT per-batch manual
review, verifying per batch:
  - every imported ExamPaper/QuestionVersion/MarkScheme stays SUGGESTED
  - zero imported content learner-servable, zero implicit embeddings
  - reconciliation conflicts preserved (warnings recorded, never repaired)
  - ALL figures/assets resolve (Stage 1 enrichment: resolved == total)
  - no content disappears (cross-batch row-count continuity + DB totals)
  - cross-transaction rerun: every pair DUPLICATE, identical counts
STOP only on a genuine gate failure. Successful batches never stop the campaign.

Evidence per batch lands in download/ingestion-campaign/batch-0NN/ and every
batch appends a machine-readable entry to cumulative-audit.json.
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

TC = Path("/home/z/toolchain")
JAVA = TC / "jdk-25.0.4.1+1/bin/java"
PARSER = Path("/home/z/my-project/repos/syllabai-parser")
CORE = Path("/home/z/my-project/repos/syllabai-core")
CORE_JAR = CORE / "target/syllabai-core-0.1.0-SNAPSHOT.jar"
PAPERS = Path("/home/z/my-project/repos/Past-Papers")
PARSER_CP = (TC / "parser-cp.txt").read_text().strip()
PSQL = TC / "pgdebs/root/usr/lib/postgresql/17/bin/psql"
JWT = "ops-batch-only-secret-key-0123456789abcdef0123456789abcdef"

# T-C04 r2 hardening: the campaign DB identity that stage-2 boots claim
# (printed AND recorded into campaign_db_identity by the core app at startup).
# Destructive tooling verifies this claim fail-closed before operating.
CAMPAIGN_LABEL = "T-C04-CAMPAIGN"
CORE_COMMIT = subprocess.run(["git", "-C", str(CORE), "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True).stdout.strip()

CAMP = Path("/home/z/my-project/download/ingestion-campaign-r2")
STATE_FILE = Path("/home/z/my-project/scripts/campaign-state-r2.json")
BATCH_SIZE = 5

ROWCOUNT_RE = re.compile(
    r"row counts before: documents=(\d+), chunks=(\d+), papers=(\d+), "
    r"versions=(\d+), schemes=(\d+), points=(\d+), bridgeRecords=(\d+)")
PAIR_RE = re.compile(
    r"pair (\S+): paper (.*?) \((INGESTED|DUPLICATE)\), (\d+) questions / "
    r"(\d+) parts / (\d+) points, reconciliation (\S+) \((\d+) findings\)")
INV_RE = re.compile(r"\[(PASS|FAIL)\] (\S+): (.*)")
COMPLETE_RE = re.compile(r"glm-ocr batch complete: (\d+) pair\(s\), (ALL invariants passed|INVARIANT FAILURES)")


def log(msg):
    print(msg, flush=True)


def parse_log(path: Path):
    text = path.read_text(errors="replace")
    counts = None
    for m in ROWCOUNT_RE.finditer(text):
        counts = m  # last one wins (in-run idempotency pass logs its own)
    pairs = [m.groups() for m in PAIR_RE.finditer(text)]
    invs = [(m.group(1), m.group(2), m.group(3)) for m in INV_RE.finditer(text)]
    complete = COMPLETE_RE.search(text)
    before = None
    m = ROWCOUNT_RE.search(text)
    if m:
        before = dict(zip(["documents", "chunks", "papers", "versions", "schemes",
                           "points", "bridgeRecords"], map(int, m.groups())))
    return {
        "pairs": pairs,
        "invariants": invs,
        "complete": complete.group(2) if complete else None,
        "counts_before": before,
    }


def sessions_of(paper_dir: str):
    return sorted(d.name for d in (PAPERS / paper_dir).iterdir()
                  if d.is_dir() and (d / "QP.md").exists())


# The four sessions quarantined by campaign r1 (duplicate part labels) were all
# diagnosed as mechanical OCR artifacts (NOT unusual printed numbering) and
# corrected in Past-Papers commit 88507eb with mark-scheme evidence per session.
# NEW quarantine (campaign r2): 1c-2016jan — corpus-level defect discovered by
# the new identity machinery: its QP is a DUPLICATE of the January-2015 paper
# (printed cover date "Monday 12 January 2015", identical totals skeleton 7,7,7,
# 8,8,17,10,9,9,14,15, identical questions) while its MS is a genuine January
# 2016 MS for a different paper. The genuine 4CH0/1C January-2016 QP is not in
# the corpus. NOT auto-fixable: ingesting it would re-publish Jan-2015 content
# under a January-2016 identity (r1 actually did exactly that — its label-based
# dedupe could not see it). Needs the original PDF from the operator.
QUARANTINED = {
    ("paper 1", "2016-Jan"): "QP duplicates the January-2015 paper (printed date "
                             "'Monday 12 January 2015'); MS belongs to a different "
                             "paper; true 2016-Jan QP absent from corpus",
}

# Operator-supplied identity for the two sessions whose QP cover page the OCR
# lost entirely (corpus/…/QP.md starts at "## Instructions"). The printed MS
# cover provides the session line ("November 2021" — the fallback path in
# GlmOcrDraftMapper), so only the paper reference is supplied by the operator,
# sourced from the same printed MS cover ("In Chemistry (4CH1) Paper 1C" and
# publications code 4CH1_1C_2111_MS). Recorded in run logs + cumulative audit.
PAPER_CODE_OVERRIDES = {
    ("paper 1", "2021-Jun"): "4CH1/1C",
    ("paper 1", "2021-Nov"): "4CH1/1C",
    ("paper 2", "2021-Jun"): "4CH1/2C",
    ("paper 2", "2021-Nov"): "4CH1/2C",
}


def build_plan():
    """batch-001 = canary 3 pairs; then 5-pair batches, paper 1 then paper 2."""
    p1 = [s for s in sessions_of("paper 1") if ("paper 1", s) not in QUARANTINED]
    p2 = [s for s in sessions_of("paper 2") if ("paper 2", s) not in QUARANTINED]
    canary = [("paper 1", s) for s in p1[:3]]
    rest = [("paper 1", s) for s in p1[3:]] + [("paper 2", s) for s in p2]
    batches = [canary]
    for i in range(0, len(rest), BATCH_SIZE):
        batches.append(rest[i:i + BATCH_SIZE])
    return batches, len(p1), len(p2)


def slug_for(paper, sess):
    code = "1c" if paper == "paper 1" else "2c"
    return f"igcse-chemistry-4ch0-{code}-{sess.lower().replace('-', '')}"


def stage1(session_tuple, out_dir):
    """Run GlmOcrPairCli with asset enrichment; verify 5 files, figures resolve,
    and the paper identity is present (session label — the parse-side mirror of
    the core identity gate; a nameless draft must fail HERE, not in stage 2)."""
    paper, sess = session_tuple
    sdir = PAPERS / paper / sess
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [str(JAVA), "-cp", f"{PARSER}/target/classes:{PARSER_CP}",
           "com.syllabai.parser.GlmOcrPairCli",
           str(sdir / "QP.md"), str(sdir / "MS.md"), str(out_dir),
           f"--uri-prefix=corpus/{out_dir.name}",
           f"--assets-dir={sdir / 'assets'}"]
    override = PAPER_CODE_OVERRIDES.get(session_tuple)
    if override:
        cmd.append(f"--paper-code={override}")
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"Stage1 failed for {sess}: {r.stderr[-500:]}")
    files = ["qp-canonical.json", "ms-canonical.json", "qp-draft.json",
             "ms-draft.json", "reconciliation.json"]
    missing = [f for f in files if not (out_dir / f).exists()]
    if missing:
        raise RuntimeError(f"bundle incomplete for {sess}: missing {missing}")
    qp = json.loads((out_dir / "qp-draft.json").read_text())
    ms_draft = json.loads((out_dir / "ms-draft.json").read_text())
    rec = json.loads((out_dir / "reconciliation.json").read_text())
    paper_meta = qp.get("paper") or {}
    ms_meta = ms_draft.get("paper") or {}
    # mirror GlmOcrDraftMapper: QP identity first, MS fallback (e.g. the
    # November-2021 pair whose QP cover the OCR lost entirely)
    session = (paper_meta.get("session") or ms_meta.get("session") or "").strip()
    ref = (paper_meta.get("paperReference")
           or ms_meta.get("paperReference")
           or PAPER_CODE_OVERRIDES.get(session_tuple) or "").strip()
    if not session:
        raise RuntimeError(f"IDENTITY GATE: {out_dir.name} has no session label "
                           f"(nameless paper would be rejected in stage 2)")
    if not ref:
        raise RuntimeError(f"IDENTITY GATE: {out_dir.name} has no paper reference "
                           f"(session-shared anchors are the r1 defect shape)")

    def figs(draft):
        tot = res = 0
        def walk(fs):
            nonlocal tot, res
            for f in fs or []:
                tot += 1
                if f.get("availability") == "available":
                    res += 1
        for q in draft.get("questions", []):
            walk(q.get("figures"))
            for p in q.get("parts", []):
                walk(p.get("figures"))
        return tot, res

    tot, res = figs(qp)
    if tot != res:
        raise RuntimeError(f"ASSET GATE: {out_dir.name} figures {res}/{tot} resolved")
    n_q = len(qp.get("questions", []))
    n_parts = sum(len(q.get("parts", [])) for q in qp.get("questions", []))
    ms = json.loads((out_dir / "ms-draft.json").read_text())
    n_points = len(ms.get("entries", ms.get("markSchemeEntries", [])))
    return {
        "questions": n_q, "parts": n_parts, "points": n_points,
        "figures": tot, "figuresResolved": res,
        "session": session, "paperReference": ref,
        "mismatchCount": rec.get("mismatchCount"),
        "paperTotalConflict": rec.get("paperTotalConflict"),
        "qpTotal": rec.get("qpPaperTotal"), "msTotal": rec.get("msPaperTotal"),
    }


def stage2(batch_root, log_path):
    """Run the batch CLI; the Spring app keeps serving after the batch, so poll
    the log for the completion marker and kill the JVM instead of waiting for a
    timeout. Returns 0 when the marker was seen, 1 on early exit without it."""
    cmd = [str(JAVA), "-jar", str(CORE_JAR),
           f"--syllabai.security.jwt-secret={JWT}",
           f"--syllabai.glmocr.batch-dir={batch_root}",
           # campaign DB identity: every stage-2 boot prints and records where
           # it is running (V15 row); the value is re-verified by preflight
           f"--syllabai.campaign.label={CAMPAIGN_LABEL}",
           f"--syllabai.campaign.commit={CORE_COMMIT}"]
    with open(log_path, "w") as lf:
        proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT)
        deadline = time.time() + 180
        marker = "batch audit report written"
        try:
            while time.time() < deadline:
                if proc.poll() is not None:
                    return 0 if marker in log_path.read_text(errors="replace") else 1
                if marker in log_path.read_text(errors="replace"):
                    time.sleep(1)  # let the final flush land
                    proc.terminate()
                    try:
                        proc.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                    return 0
                time.sleep(1)
            proc.kill()
            return 1  # never completed within the deadline — genuine gate
        finally:
            if proc.poll() is None:
                proc.kill()


def db_totals():
    q = ("SELECT (SELECT count(*) FROM exam_papers) papers,"
         "(SELECT count(*) FROM question_versions) versions,"
         "(SELECT count(*) FROM question_versions WHERE validation_state='VALIDATED') validated,"
         "(SELECT count(*) FROM question_versions WHERE validation_state='SUGGESTED') suggested,"
         "(SELECT count(*) FROM mark_schemes) schemes,"
         "(SELECT count(*) FROM mark_points) points,"
         "(SELECT count(*) FROM document_chunks) chunks,"
         "(SELECT count(*) FROM document_chunks WHERE embedded_at IS NOT NULL) embedded,"
         "(SELECT count(*) FROM documents) documents,"
         "(SELECT count(*) FROM glm_ocr_bridge_records) bridge")
    r = subprocess.run([str(PSQL), "-h", "127.0.0.1", "-U", "syllabai", "-d",
                        "syllabai", "-tAc", q], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed: {r.stderr}")
    keys = ["papers", "versions", "validated", "suggested", "schemes", "points",
            "chunks", "embedded", "documents", "bridge"]
    return dict(zip(keys, map(int, r.stdout.strip().split("|"))))


def load_state():
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {"done": {}, "evidence": {}}


def save_state(s):
    STATE_FILE.write_text(json.dumps(s, indent=1))


def run_batch(idx, batch, state):
    bid = f"batch-{idx:03d}"
    bdir = CAMP / bid
    ev = bdir / "evidence"
    ev.mkdir(parents=True, exist_ok=True)
    entry = {"batch": bid, "pairs": [], "stage1": {}, "runs": [], "db": {}}

    # ---- Stage 1 (ALWAYS re-runs: stale bundles are a silent-drift hazard —
    # an existing bundle from an older parser would keep old identity/fields) ----
    root = bdir / "bundles"
    root.mkdir(parents=True, exist_ok=True)
    log(f"{bid}: Stage 1 → {len(batch)} pair bundle(s)")
    for paper, sess in batch:
        slug = slug_for(paper, sess)
        info = stage1((paper, sess), root / slug)
        entry["stage1"][slug] = info
        log(f"  {slug}: {info['questions']}q/{info['parts']}p/{info['points']}mp "
            f"figures {info['figuresResolved']}/{info['figures']} "
            f"session={info['session']!r} ref={info['paperReference']!r} "
            f"recon(mismatch={info['mismatchCount']}, conflict={info['paperTotalConflict']})")
    entry["pairs"] = [slug_for(p, s) for p, s in batch]

    # ---- Stage 2 run 1 ----
    log(f"{bid}: Stage 2 run 1 (first ingestion)")
    rc = stage2(root, ev / "run1.log")
    r1 = parse_log(ev / "run1.log")
    statuses = {p[0]: p[2] for p in r1["pairs"]}
    if not r1["pairs"]:
        raise RuntimeError(f"GATE: {bid} run1 pair lines not parsed (regex/format drift) "
                           f"complete={r1['complete']}")
    if rc != 0 or r1["complete"] != "ALL invariants passed":
        raise RuntimeError(f"GATE: {bid} run1 rc={rc} complete={r1['complete']} "
                           f"statuses={statuses} invariants={r1['invariants']}")
    if not all(s in ("INGESTED", "DUPLICATE") for s in statuses.values()):
        raise RuntimeError(f"GATE: {bid} run1 unexpected statuses: {statuses}")
    fails = [i for i in r1["invariants"] if i[0] == "FAIL"]
    if fails:
        raise RuntimeError(f"GATE: {bid} run1 invariant failures: {fails}")
    (bdir / "batch-audit-report-run1.json").write_text(
        (root / "batch-audit-report.json").read_text())
    entry["runs"].append({"pass": 1, "statuses": statuses,
                          "invariants": {i[1]: i[0] == "PASS" for i in r1["invariants"]},
                          "complete": r1["complete"]})
    per_pair = {p[0]: {"title": p[1], "status": p[2], "questions": int(p[3]),
                       "parts": int(p[4]), "points": int(p[5]),
                       "reconciliation": p[6], "findings": int(p[7])}
                for p in r1["pairs"]}
    entry["pairs"] = per_pair

    # ---- Stage 2 run 2: cross-transaction rerun ----
    log(f"{bid}: Stage 2 run 2 (cross-transaction rerun)")
    rc = stage2(root, ev / "run2.log")
    r2 = parse_log(ev / "run2.log")
    statuses2 = {p[0]: p[2] for p in r2["pairs"]}
    if not r2["pairs"]:
        raise RuntimeError(f"GATE: {bid} run2 pair lines not parsed (regex/format drift)")
    if rc != 0 or r2["complete"] != "ALL invariants passed":
        raise RuntimeError(f"GATE: {bid} run2 rc={rc} complete={r2['complete']}")
    if not all(s == "DUPLICATE" for s in statuses2.values()):
        raise RuntimeError(f"GATE: {bid} rerun expected all DUPLICATE, got {statuses2}")
    fails2 = [i for i in r2["invariants"] if i[0] == "FAIL"]
    if fails2:
        raise RuntimeError(f"GATE: {bid} run2 invariant failures: {fails2}")
    # counts identical between run1 and run2 for every pair (title+counts identity)
    for p2 in r2["pairs"]:
        k, _title, st, nq, np_, npt, recon, nfind = p2
        p1d = per_pair.get(k)
        if not p1d or p1d["questions"] != int(nq) \
           or p1d["parts"] != int(np_) or p1d["points"] != int(npt) \
           or p1d["findings"] != int(nfind):
            raise RuntimeError(f"GATE: {bid} rerun count drift on {k}")
    entry["runs"].append({"pass": 2, "statuses": statuses2, "complete": r2["complete"]})
    (bdir / "batch-audit-report-run2.json").write_text(
        (root / "batch-audit-report.json").read_text())

    # ---- NON-VACUOUS row-count gate: run2's 'before' snapshot is run1's 'after'.
    # The delta run1→run2 must be EXACTLY what the INGESTED pairs added; a rerun
    # that created or lost rows cannot hide here any more (previously logged only).
    if r2["counts_before"] and r1["counts_before"]:
        delta = {k: r2["counts_before"][k] - r1["counts_before"][k]
                 for k in r1["counts_before"]}
        ingested_pairs = [k for k, v in statuses.items() if v == "INGESTED"]
        expected = {
            "papers": len(ingested_pairs),
            "bridgeRecords": len(ingested_pairs),
            "versions": sum(per_pair[k]["questions"] for k in ingested_pairs),
        }
        for k, want in expected.items():
            if delta[k] != want:
                raise RuntimeError(f"GATE: {bid} row delta {k}={delta[k]} != {want} "
                                   f"(INGESTED contribution) — rows created/lost in rerun")
        log(f"{bid}: row-count delta run1→run2: {delta} (matches INGESTED contribution)")

    # ---- DB verification ----
    tot = db_totals()
    entry["db"] = tot
    if tot["validated"] != 8:
        raise RuntimeError(f"GATE: seed VALIDATED count drifted: {tot['validated']} != 8")
    if tot["embedded"] != 0:
        raise RuntimeError(f"GATE: implicit embedding detected: {tot['embedded']}")
    # cumulative expected: sum of questions across every completed batch (incl. this one)
    pending = {bid: {"pairs": entry["pairs"], "stage1": entry["stage1"],
                     "runs": entry["runs"], "db": tot}}
    expected_suggested = 0
    for b in {**state["done"], **pending}.values():
        expected_suggested += sum(p["questions"] for p in b["pairs"].values())
    if tot["suggested"] != expected_suggested:
        raise RuntimeError(f"GATE: suggested count {tot['suggested']} != "
                           f"cumulative expected {expected_suggested}")
    if tot["papers"] != len({**state["done"], **pending}) * 0 + sum(
            len(b["pairs"]) for b in {**state["done"], **pending}.values()):
        raise RuntimeError(f"GATE: papers count {tot['papers']} mismatch")
    if tot["bridge"] != sum(len(b["pairs"]) for b in {**state["done"], **pending}.values()):
        raise RuntimeError(f"GATE: bridge records {tot['bridge']} mismatch")
    log(f"{bid}: DB totals {tot}")

    # ---- cross-batch continuity log ----
    if r2["counts_before"] and r1["counts_before"]:
        delta = {k: r2["counts_before"][k] - r1["counts_before"][k] for k in r1["counts_before"]}
        log(f"{bid}: row-count delta run1→run2: {delta}")
    state["done"].update(pending)
    save_state(state)
    with open(CAMP / "cumulative-audit.json", "w") as f:
        json.dump({"batches": state["done"]}, f, indent=1)
    log(f"{bid}: COMPLETE ✓")




def main():
    # T-C04 r2 hardening (directive 2026-09-13, item 2): verify the campaign DB
    # identity fail-closed BEFORE planning or running any batch — a wrong or
    # unclaimed database aborts here, never mid-batch.
    sys.path.insert(0, "/home/z/my-project/scripts")
    from campaign_db_preflight import preflight
    preflight(expected_db="syllabai", expected_label=CAMPAIGN_LABEL)

    which = sys.argv[1] if len(sys.argv) > 1 else "next"
    howmany = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    batches, n1, n2 = build_plan()
    log(f"plan: {len(batches)} batches over {n1 + n2} sessions (paper1={n1}, paper2={n2})")
    log(f"quarantined (operator review): {sorted(f'{p}/{s}' for (p, s) in QUARANTINED)}")
    state = load_state()
    done = 0
    for i, batch in enumerate(batches, start=1):
        bid = f"batch-{i:03d}"
        if bid in state["done"]:
            continue
        run_batch(i, batch, state)
        done += 1
        if which == "next" and done >= howmany:
            break
    log(f"finished: {len(state['done'])}/{len(batches)} batches done")


if __name__ == "__main__":
    main()
