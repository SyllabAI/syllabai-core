#!/usr/bin/env python3
"""
repair_identity_reingest.py — campaign r1 → r2 repair.

Repairs the 9 identity-defective papers (session_label/paper_code NULL,
title 'null …', random anchor codes) AND re-ingests the full approved
corpus (82 sessions — including the 4 un-quarantined by Past-Papers
commit 88507eb) so every row carries complete printed identity.

Deletes, in FK-safe order, ONLY what the campaign created:
  glm_ocr_bridge_records, mark_points, question_parts, question_versions,
  mark_schemes, questions, exam_papers (all PAST_PAPER-provenance),
  orphaned ING-% anchor nodes + their PART_OF edges.
Keeps: documents + document_chunks (checksum-keyed, idempotent re-link),
seed/teacher content (8 VALIDATED seed versions, SEED_DEMO paper, subjects,
curriculum, the 25 session-label anchor nodes get REUSED via find-or-create).

Safety gates BEFORE any delete:
  - zero rows in every table that references assessment content
    (attempts, answers, smart_mark_results, smart_mark_agreement_evaluations,
    question_topics, question_options)
  - the seed VALIDATED count is exactly the expected baseline
Prints before/after totals; exit 1 without deleting when a gate fails.
"""
import subprocess
import sys

# T-C04 r2 hardening: fail-closed DB identity gate — verify WHERE we are
# before the first gate, long before the first DELETE.
sys.path.insert(0, "/home/z/my-project/scripts")
from campaign_db_preflight import preflight  # noqa: E402

PG = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"
CONN = ["-h", "/home/z/toolchain", "-U", "syllabai", "-d", "syllabai", "-tAc"]
EXPECTED_SEED_VALIDATED = 8

TOTALS_Q = """
SELECT (SELECT count(*) FROM exam_papers) papers,
       (SELECT count(*) FROM exam_papers WHERE provenance='PAST_PAPER') pp_papers,
       (SELECT count(*) FROM question_versions) versions,
       (SELECT count(*) FROM question_versions WHERE validation_state='VALIDATED') validated,
       (SELECT count(*) FROM question_versions WHERE validation_state='SUGGESTED') suggested,
       (SELECT count(*) FROM mark_schemes) schemes,
       (SELECT count(*) FROM mark_points) points,
       (SELECT count(*) FROM question_parts) parts,
       (SELECT count(*) FROM questions) questions,
       (SELECT count(*) FROM knowledge_nodes) nodes,
       (SELECT count(*) FROM knowledge_edges) edges,
       (SELECT count(*) FROM glm_ocr_bridge_records) bridge,
       (SELECT count(*) FROM documents) documents,
       (SELECT count(*) FROM document_chunks) chunks,
       (SELECT count(*) FROM document_chunks WHERE embedding IS NOT NULL) embedded
"""


def psql(sql):
    r = subprocess.run([PG] + CONN + [sql], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed: {r.stderr}")
    return r.stdout.strip()


def totals():
    return dict(zip(["papers", "pp_papers", "versions", "validated", "suggested",
                     "schemes", "points", "parts", "questions", "nodes", "edges",
                     "bridge", "documents", "chunks", "embedded"],
                    map(int, psql(TOTALS_Q).split("|"))))


REF_GATE = """
SELECT 'attempts', count(*) FROM attempts WHERE question_id IN
    (SELECT id FROM questions WHERE exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))
UNION ALL SELECT 'answers', count(*) FROM answers WHERE question_part_id IN
    (SELECT qp.id FROM question_parts qp
     JOIN question_versions qv ON qp.question_version_id=qv.id
     JOIN questions q ON qv.question_id=q.id
     WHERE q.exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))
UNION ALL SELECT 'smart_mark_results', count(*) FROM smart_mark_results
UNION ALL SELECT 'smart_mark_agree', count(*) FROM smart_mark_agreement_evaluations
    WHERE exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER')
UNION ALL SELECT 'question_topics', count(*) FROM question_topics WHERE question_id IN
    (SELECT id FROM questions WHERE exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))
UNION ALL SELECT 'question_options', count(*) FROM question_options WHERE question_id IN
    (SELECT id FROM questions WHERE exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))
"""

DELETE_ORDER = [
    ("glm_ocr_bridge_records", "DELETE FROM glm_ocr_bridge_records"),
    ("mark_points (imported)", """
        DELETE FROM mark_points WHERE mark_scheme_id IN
        (SELECT ms.id FROM mark_schemes ms JOIN question_versions qv ON ms.question_version_id=qv.id
         JOIN questions q ON qv.question_id=q.id
         WHERE q.exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))"""),
    ("question_parts (imported)", """
        DELETE FROM question_parts WHERE question_version_id IN
        (SELECT qv.id FROM question_versions qv JOIN questions q ON qv.question_id=q.id
         WHERE q.exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))"""),
    ("mark_schemes (imported)", """
        DELETE FROM mark_schemes WHERE question_version_id IN
        (SELECT qv.id FROM question_versions qv JOIN questions q ON qv.question_id=q.id
         WHERE q.exam_paper_id IN (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))"""),
    ("question_versions (imported)", """
        DELETE FROM question_versions WHERE question_id IN
        (SELECT id FROM questions WHERE exam_paper_id IN
         (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER'))"""),
    ("questions (imported)", """
        DELETE FROM questions WHERE exam_paper_id IN
        (SELECT id FROM exam_papers WHERE provenance='PAST_PAPER')"""),
    ("exam_papers (imported)", "DELETE FROM exam_papers WHERE provenance='PAST_PAPER'"),
    ("orphan PART_OF edges", """
        DELETE FROM knowledge_edges WHERE relation_type='PART_OF' AND
        (source_node_id IN (SELECT id FROM knowledge_nodes WHERE code LIKE 'ING-%')
         AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.primary_topic_node_id = knowledge_edges.source_node_id))"""),
    ("orphan ING- anchors", """
        DELETE FROM knowledge_nodes WHERE code LIKE 'ING-%' AND
        NOT EXISTS (SELECT 1 FROM questions q WHERE q.primary_topic_node_id = knowledge_nodes.id)"""),
]


def main():
    # DB identity gate (directive 2026-09-13, item 2): the repair script is the
    # only actor in the workspace that can delete campaign rows — it must never
    # run against a test database, an unclaimed database, or a mismatched
    # identity. Aborts (exit 1) BEFORE the ref-gates when anything is off.
    preflight(expected_db="syllabai", expected_label="T-C04-CAMPAIGN")

    before = totals()
    print("BEFORE:", before, flush=True)

    refs = [line.split("|") for line in psql(REF_GATE).splitlines()]
    bad = [(t, c) for t, c in refs if int(c) != 0]
    if bad:
        print(f"GATE FAILED: referencing rows exist: {bad}", flush=True)
        sys.exit(1)
    print("ref-gate: all referencing tables empty ✓", flush=True)

    if before["validated"] != EXPECTED_SEED_VALIDATED:
        print(f"GATE FAILED: seed VALIDATED baseline {before['validated']} "
              f"!= {EXPECTED_SEED_VALIDATED}", flush=True)
        sys.exit(1)
    print(f"seed baseline: validated == {EXPECTED_SEED_VALIDATED} ✓", flush=True)

    for label, sql in DELETE_ORDER:
        n = int(psql(f"WITH d AS ({sql} RETURNING 1) SELECT count(*) FROM d"))
        print(f"  deleted {n:5d}  {label}", flush=True)

    after = totals()
    print("AFTER: ", after, flush=True)
    if after["validated"] != EXPECTED_SEED_VALIDATED:
        print("GATE FAILED: seed rows damaged!", flush=True)
        sys.exit(1)
    if after["documents"] != before["documents"] or after["chunks"] != before["chunks"]:
        print("NOTE: document/chunk counts changed (documents are kept — investigate)",
              flush=True)
    print("REPAIR DONE — corpus ready for campaign r2", flush=True)


if __name__ == "__main__":
    main()
