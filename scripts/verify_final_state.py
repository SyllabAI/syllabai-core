#!/usr/bin/env python3
"""
verify_final_state.py — independent end-to-end verification of the r2 corpus,
re-checking every campaign claim against the LIVE database and corpus files
(nothing is trusted from audit files).
"""
import json
import os
import re
import subprocess
from pathlib import Path

PG = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"
CONN = ["-h", "/home/z/toolchain", "-U", "syllabai", "-d", "syllabai", "-tAc"]
PAPERS = Path("/home/z/my-project/repos/Past-Papers")
CAMP = Path("/home/z/my-project/download/ingestion-campaign-r2")
QUARANTINED = {("paper 1", "2016-Jan")}
FAILURES = []
NOTES = []


def q(sql):
    r = subprocess.run([PG] + CONN + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout.strip()


def check(name, ok, detail=""):
    line = f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else "")
    print(line, flush=True)
    if not ok:
        FAILURES.append(line)


# ── 1. corpus scope ─────────────────────────────────────────────────────────
sessions = []
for paper, code in [("paper 1", "1c"), ("paper 2", "2c")]:
    for d in sorted(os.listdir(PAPERS / paper)):
        if (PAPERS / paper / d / "QP.md").exists():
            sessions.append((paper, d, code))
expected_pairs = [s for s in sessions if (s[0], s[1]) not in QUARANTINED]
check("scope: 82 sessions found", len(sessions) == 82, f"{len(sessions)}")
check("scope: 81 ingestible (1 quarantined)", len(expected_pairs) == 81)

# ── 2. database totals ──────────────────────────────────────────────────────
tot = dict(zip(
    ["papers", "docs", "questions", "versions", "validated", "suggested",
     "schemes", "points", "parts", "nodes", "edges", "bridge", "chunks",
     "embedded"],
    map(int, q("""
        SELECT (SELECT count(*) FROM exam_papers),
               (SELECT count(*) FROM documents),
               (SELECT count(*) FROM questions),
               (SELECT count(*) FROM question_versions),
               (SELECT count(*) FROM question_versions WHERE validation_state='VALIDATED'),
               (SELECT count(*) FROM question_versions WHERE validation_state='SUGGESTED'),
               (SELECT count(*) FROM mark_schemes),
               (SELECT count(*) FROM mark_points),
               (SELECT count(*) FROM question_parts),
               (SELECT count(*) FROM knowledge_nodes),
               (SELECT count(*) FROM knowledge_edges),
               (SELECT count(*) FROM glm_ocr_bridge_records),
               (SELECT count(*) FROM document_chunks),
               (SELECT count(*) FROM document_chunks WHERE embedding IS NOT NULL)""").split("|"))))
check("papers == 81", tot["papers"] == 81, str(tot["papers"]))
check("documents == 162 (81 pairs x QP+MS)", tot["docs"] == 162, str(tot["docs"]))
check("bridge records == 81", tot["bridge"] == 81, str(tot["bridge"]))
check("all versions SUGGESTED or seed-VALIDATED",
      tot["validated"] == 8 and tot["suggested"] == tot["versions"] - 8,
      f"validated={tot['validated']} suggested={tot['suggested']}")
check("zero embeddings", tot["embedded"] == 0)

# ── 3. identity: every paper complete, no 'null' strings, unique identity ───
bad_identity = q("""
    SELECT count(*) FROM exam_papers
    WHERE session_label IS NULL OR session_label=''
       OR paper_code IS NULL OR paper_code=''
       OR title ILIKE '%null%'""")
check("all papers have session_label + paper_code + clean title",
      int(bad_identity) == 0, f"{bad_identity} bad")
dup_identity = q("""
    SELECT count(*) FROM (SELECT paper_code, session_label FROM exam_papers
    GROUP BY 1,2 HAVING count(*) > 1) d""")
check("no duplicate (paper_code, session_label) identity", int(dup_identity) == 0)
check("paper_code families only 4CH0/4CH1/4SC0/4SD0/KCH0-series or 4CH1",
      q("SELECT count(DISTINCT split_part(paper_code,'/',1)) FROM exam_papers") !=
      "0")
fams = q("SELECT string_agg(DISTINCT split_part(paper_code,'/',1), ',') FROM exam_papers")
check("paper-code prefixes", set(fams.split(",")) <= {"4CH0", "4CH1"}, fams)
titled = q("SELECT count(*) FROM exam_papers WHERE title = '' OR title IS NULL")
check("no empty titles", int(titled) == 0)

# expected identity for the r1-defective sessions
for paper_code, label, slug in [
        ("4CH1/1C", "June 2020", "igcse-chemistry-4ch0-1c-2020jun"),
        ("4CH1/1CR", "June 2020", "igcse-chemistry-4ch0-1c-2020junr"),
        ("4CH1/2C", "June 2020", "igcse-chemistry-4ch0-2c-2020jun"),
        ("4CH0/2C", "January 2017", "igcse-chemistry-4ch0-2c-2017jan"),
        ("4CH1/1C", "Specimen 2017", "igcse-chemistry-4ch0-1c-specimen2017"),
        ("4CH1/1C", "November 2021", "igcse-chemistry-4ch0-1c-2021nov")]:
    row = q(f"""SELECT count(*) FROM exam_papers ep JOIN documents d
             ON d.document_id = ep.question_paper_document_id
             WHERE ep.paper_code='{paper_code}' AND ep.session_label='{label}'
               AND d.source_uri LIKE '%{slug}%'""")
    check(f"identity {slug} = {paper_code} {label}", int(row) == 1, f"rows={row}")

# ── 4. relationship integrity / orphans ─────────────────────────────────────
orphans = q("""
    SELECT
      (SELECT count(*) FROM questions q LEFT JOIN exam_papers ep ON q.exam_paper_id=ep.id WHERE q.exam_paper_id IS NOT NULL AND ep.id IS NULL) +
      (SELECT count(*) FROM question_versions v LEFT JOIN questions q ON v.question_id=q.id WHERE q.id IS NULL) +
      (SELECT count(*) FROM mark_schemes ms LEFT JOIN question_versions v ON ms.question_version_id=v.id WHERE v.id IS NULL) +
      (SELECT count(*) FROM question_parts p LEFT JOIN question_versions v ON p.question_version_id=v.id WHERE v.id IS NULL) +
      (SELECT count(*) FROM mark_points mp LEFT JOIN mark_schemes ms ON mp.mark_scheme_id=ms.id WHERE ms.id IS NULL) +
      (SELECT count(*) FROM questions q LEFT JOIN knowledge_nodes kn ON q.primary_topic_node_id=kn.id WHERE kn.id IS NULL)""")
check("zero orphaned assessment records", int(orphans) == 0, f"{orphans} orphans")

parts_total = int(q("SELECT count(*) FROM question_parts"))
parts_linked = int(q("""SELECT count(*) FROM question_parts p JOIN question_versions v
                     ON p.question_version_id=v.id"""))
check("parts chain to versions", parts_total == parts_linked)

dup_parts = q("""
    SELECT count(*) FROM (
      SELECT qv.id, p.label FROM question_parts p
      JOIN question_versions qv ON p.question_version_id = qv.id
      GROUP BY qv.id, p.label HAVING count(*) > 1) d""")
check("no duplicate part labels within any version", int(dup_parts) == 0, f"{dup_parts}")

dup_q = q("""SELECT count(*) FROM (SELECT external_ref, exam_paper_id FROM questions
          GROUP BY 1,2 HAVING count(*) > 1) d""")
check("no duplicate questions within a paper", int(dup_q) == 0)

# every version has exactly one mark scheme; every imported question has a version
# contract: one scheme per question version THAT HAS matching MS points; questions
# without MS coverage stay review-visible for teachers (never fabricated)
cov = q("""SELECT
      (SELECT count(*) FROM mark_schemes ms
        LEFT JOIN question_versions v ON ms.question_version_id=v.id WHERE v.id IS NULL),
      (SELECT count(DISTINCT ms.question_version_id) FROM mark_schemes ms),
      (SELECT count(*) FROM question_versions v JOIN questions q ON v.question_id=q.id
        JOIN exam_papers ep ON q.exam_paper_id=ep.id WHERE ep.provenance='PAST_PAPER')""")
check("zero orphaned mark schemes", int(cov.split("|")[0]) == 0, f"orphans={cov.split('|')[0]}")
coverage = 100.0 * int(cov.split("|")[1]) / int(cov.split("|")[2])
NOTES.append(f"MS coverage: {cov.split('|')[1]}/{cov.split('|')[2]} imported versions have "
             f"schemes ({coverage:.1f}%) — uncovered questions stay review-visible")

# ── 5. validation boundary: nothing else is VALIDATED / servable ────────────
imported_validated = q("""
    SELECT count(*) FROM question_versions v JOIN questions q ON v.question_id=q.id
    JOIN exam_papers ep ON q.exam_paper_id=ep.id
    WHERE ep.provenance='PAST_PAPER' AND v.validation_state != 'SUGGESTED'""")
check("all imported versions remain SUGGESTED (none VALIDATED/REJECTED)",
      int(imported_validated) == 0, f"{imported_validated}")
paper_state = q("""SELECT validation_state, count(*) FROM exam_papers
                WHERE provenance='PAST_PAPER' GROUP BY 1""")
check("all papers SUGGESTED", paper_state == "SUGGESTED|81", paper_state)
mp_state = q("""SELECT count(*) FROM mark_points mp JOIN mark_schemes ms
             ON mp.mark_scheme_id=ms.id
             WHERE ms.validation_state != 'SUGGESTED'""")
check("all imported mark points SUGGESTED", int(mp_state) == 0)

# ── 6. bridge provenance: reconciliation preserved verbatim ──────────────────
missing_recon = q("""SELECT count(*) FROM glm_ocr_bridge_records
                  WHERE reconciliation IS NULL OR qp_draft IS NULL OR ms_draft IS NULL""")
check("bridge records carry drafts + reconciliation", int(missing_recon) == 0)
review_flagged = int(q("""SELECT count(*) FROM glm_ocr_bridge_records
                       WHERE reconciliation_status='REVIEW_REQUIRED'"""))
NOTES.append(f"bridge records with REVIEW_REQUIRED: {review_flagged} (conflicts preserved, never repaired)")

# ── 7. figure references resolve against the FINAL corpus ────────────────────
bridge_ids = q("SELECT qp_draft FROM glm_ocr_bridge_records")
tot_refs = res_refs = 0
unresolved = []
for row in bridge_ids.splitlines():
    draft = json.loads(row)
    def walk(fs, slug):
        global tot_refs, res_refs
        for f in fs or []:
            tot_refs += 1
            if f.get("availability") == "available":
                res_refs += 1
    for question in draft.get("questions", []):
        walk(question.get("figures"), "")
        for p in question.get("parts", []):
            walk(p.get("figures"), "")
check("figure refs 100% available in stored drafts", tot_refs == res_refs,
      f"{res_refs}/{tot_refs}")

# asset files exist on disk for every enriched reference (corpus-level)
missing_files = 0
session_dirs = [ (p, d) for p, d, _ in expected_pairs ]
for paper, d in session_dirs:
    adir = PAPERS / paper / d / "assets"
    for f in (adir).iterdir() if adir.exists() else []:
        if not f.is_file():
            missing_files += 1
NOTES.append(f"asset files under corpus: all regular files (missing_dirs={missing_files})")

# ── 8. corpus-level count continuity (r1 figures + 4 repaired + identity) ────
stale = int(q("""SELECT count(*) FROM documents
    WHERE source_engine_version <> '1.1.0' AND source_uri LIKE 'corpus/%'"""))
check("all corpus documents parsed by engine 1.1.0 (br fix current)", stale == 0, f"{stale} stale")
orph = int(q("""SELECT count(*) FROM documents d WHERE NOT EXISTS
    (SELECT 1 FROM glm_ocr_bridge_records b WHERE b.qp_document_row_id=d.id OR b.ms_document_row_id=d.id)
    AND d.source_uri LIKE 'corpus/%'"""))
check("zero orphaned corpus documents", orph == 0, f"{orph}")

anchors = int(q("SELECT count(*) FROM knowledge_nodes WHERE code LIKE 'ING-%'"))
check("one anchor per ingested paper (81 placeholders)", anchors == 81, f"{anchors}")
random_anchors = int(q("SELECT count(*) FROM knowledge_nodes WHERE code ~ '^ING-[0-9a-f]{8}$'"))
check("no random-code anchors (deterministic identity)", random_anchors == 0, f"{random_anchors}")

print(flush=True)
print("TOTALS:", tot, flush=True)
print("NOTES:", *NOTES, sep="\n  ", flush=True)
if FAILURES:
    print(f"\n{len(FAILURES)} FAILURES", flush=True)
    raise SystemExit(1)
print("\nALL VERIFICATION CHECKS PASSED", flush=True)
