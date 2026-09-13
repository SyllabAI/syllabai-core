#!/usr/bin/env python3
"""
build_baseline.py — durable campaign baseline (operator directive 2026-09-13,
item 9). Captures the complete reproducibility contract of the T-C04 r2 final
state into download/campaign-baseline/:

  corpus commit SHA, parser commit SHA, core commit SHA,
  database migration/schema version, engine version,
  batch manifest, final row counts, figure manifest checksum,
  verification result (incl. the DB/test isolation proof verdict).
"""
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

REPOS = Path("/home/z/my-project/repos")
STATE = Path("/home/z/my-project/scripts/campaign-state-r2.json")
OUT = Path("/home/z/my-project/download/campaign-baseline")
OUT.mkdir(parents=True, exist_ok=True)
PSQL = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"
CONN = ["-h", "127.0.0.1", "-p", "5432", "-U", "syllabai", "-d", "syllabai", "-tAc"]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_sha(repo: str) -> str:
    return subprocess.run(["git", "-C", str(REPOS / repo), "rev-parse", "HEAD"],
                          capture_output=True, text=True).stdout.strip()


def q(sql: str) -> str:
    return subprocess.run(["/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"]
                          + CONN + [sql], capture_output=True, text=True).stdout.strip()


baseline = {
    "generated_at_utc": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
    "campaign": "T-C04 r2 final — 81/82 sessions ingested, 1c-2016jan quarantined (operator/PDF resolution required)",

    "commit_shas": {
        "corpus_past_papers": git_sha("Past-Papers"),
        "syllabai_parser": git_sha("syllabai-parser"),
        "syllabai_core": git_sha("syllabai-core"),
    },

    "schema": {
        "migration_tool": "flyway (classpath:db/migration)",
        "migration_version": q("SELECT max(version::int) FROM flyway_schema_history"),
        "migration_head": q("SELECT description FROM flyway_schema_history "
                            "WHERE installed_rank = (SELECT max(installed_rank) "
                            "FROM flyway_schema_history)"),
        "note": "V15 = campaign_db_identity (isolation hardening); identity row claimed T-C04-CAMPAIGN",
    },

    "engine": {
        "parser_engine_version": "1.1.0 (<br> printed-line-break fix current)",
        "extraction": "opendataloader-fast+heuristics + glm-ocr markdown adapter",
    },

    "db_identity": {
        "campaign_label": q("SELECT campaign_label FROM campaign_db_identity WHERE id=1"),
        "db_name": q("SELECT db_name FROM campaign_db_identity WHERE id=1"),
        "claim_rule": "destructive tooling runs campaign_db_preflight.py: missing/mismatched identity → fail closed",
    },

    "row_counts": {
        "exam_papers": int(q("SELECT count(*) FROM exam_papers")),
        "documents": int(q("SELECT count(*) FROM documents")),
        "questions": int(q("SELECT count(*) FROM questions")),
        "question_versions": int(q("SELECT count(*) FROM question_versions")),
        "question_versions_suggested": int(q("SELECT count(*) FROM question_versions WHERE validation_state='SUGGESTED'")),
        "question_versions_validated_seed": int(q("SELECT count(*) FROM question_versions WHERE validation_state='VALIDATED'")),
        "mark_schemes": int(q("SELECT count(*) FROM mark_schemes")),
        "mark_points": int(q("SELECT count(*) FROM mark_points")),
        "question_parts": int(q("SELECT count(*) FROM question_parts")),
        "knowledge_nodes": int(q("SELECT count(*) FROM knowledge_nodes")),
        "knowledge_edges": int(q("SELECT count(*) FROM knowledge_edges")),
        "glm_ocr_bridge_records": int(q("SELECT count(*) FROM glm_ocr_bridge_records")),
        "document_chunks": int(q("SELECT count(*) FROM document_chunks")),
        "document_chunks_embedded": int(q("SELECT count(*) FROM document_chunks WHERE embedding IS NOT NULL")),
    },

    "figures": {
        "figure_refs_in_imported_drafts": 1507,
        "resolved": 1507,
        "manifest_checksums": {
            "paper 1/MANIFEST.json": sha(REPOS / "Past-Papers/paper 1/MANIFEST.json"),
            "paper 2/MANIFEST.json": sha(REPOS / "Past-Papers/paper 2/MANIFEST.json"),
        },
    },

    "batch_manifest": {
        "total_batches": 17,
        "source": "download/ingestion-campaign-r2/ (per-batch evidence: run1/run2 audit reports + logs + bundles)",
        "batches": {
            bid: {p: {"status": v["status"], "questions": v["questions"],
                      "parts": v["parts"], "points": v["points"]}
                  for p, v in b["pairs"].items()}
            for bid, b in json.loads(STATE.read_text())["done"].items()
        },
    },

    "quarantine": {
        "session": "1c-2016jan (paper 1, January 2016)",
        "evidence": [
            "QP prints January 2015 and duplicates the already-ingested January-2015 paper",
            "its paired MS identifies a different January-2016 paper",
            "the genuine 4CH0/1C January-2016 QP is absent from the corpus",
        ],
        "resolution": "requires original-source/operator resolution — never auto-fixed",
        "paper2_jan2016_ingested": "4CH0/2C January 2016 (unaffected)",
    },

    "isolation_hardening": {
        "root_cause_assessment": "bridge-record disappearance mechanism = repair script delete phase "
                                 "(only DB actor with DELETE power; stdout-only logging); core test suite "
                                 "carried a latent hole (campaign DB default leaked into context tests) — closed",
        "mechanisms": [
            "V15 campaign_db_identity + CampaignDbIdentity startup claim (prints + records)",
            "RequireTestDatabase guard (autodetected, fail-closed: tests target syllabai_test only)",
            "campaign_db_preflight.py wired into repair_identity_reingest.py + run_ingestion_campaign.py",
            "provision_test_db.sh (disposable DB, identity stays UNCLAIMED)",
            "run_tests_isolated.sh (full-suite snapshot/diff proof)",
        ],
        "proof": "download/isolation-proof/verdict.txt — 334/334 green, campaign DB byte-identical (row counts + data dump sha256)",
        "core_tests": "334 run, 0 failed, 0 skipped (live isolation check included)",
        "parser_tests": "99 run, 0 failed",
    },

    "verification_result": {
        "final_state_checks": "ALL VERIFICATION CHECKS PASSED (26 checks — identity spot-checks, "
                              "orphans, part-label uniqueness, SUGGESTED boundary, figure resolution, "
                              "engine-version currency)",
        "boundaries": "0 imported rows VALIDATED/REJECTED; seed 8 VALIDATED untouched; 0 embedded; "
                      "STRUCTURED serves only at VALIDATED → 0 servable",
        "next_stage_unblocked": ["teacher validation", "review UI work", "graph/resource work",
                                 "learner-model work", "unrelated corpus processing"],
    },
}

(OUT / "campaign-baseline.json").write_text(json.dumps(baseline, indent=2) + "\n")
print("written:", OUT / "campaign-baseline.json")
print("core:", baseline["commit_shas"]["syllabai_core"], "| parser:",
      baseline["commit_shas"]["syllabai_parser"], "| corpus:",
      baseline["commit_shas"]["corpus_past_papers"])
print("schema:", baseline["schema"]["migration_version"], "-",
      baseline["schema"]["migration_head"])
print("identity:", baseline["db_identity"]["campaign_label"], "@",
      baseline["db_identity"]["db_name"])
print("counts:", baseline["row_counts"])
