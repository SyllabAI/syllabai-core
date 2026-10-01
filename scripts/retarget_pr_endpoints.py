#!/usr/bin/env python3
"""
Retarget the 12 validated practical REQUIRES_PREREQUISITE edges in
src/main/resources/concept-graph/concept_edges.yaml from the ad-hoc
practical-node codes (4CH1-PR-01..11) to their real core-practical spec
statements — the upstream lockstep of the syllabai-hub mirror retarget
(scripts/fix_pr_edges.py fd05d75 + scripts/fix_pr_mirror.py), per the
Batch-5 manifest follow-up 1 (docs/TC11_BATCH5_MANIFEST.md).

WHY A SURGICAL SCRIPT: the store header says "DO NOT hand-edit: re-run
the script", but the c09/c11 generator toolchain and its decision-record
registry are not in this repo (they lived in the retired analysis
workspace; the registry is a frozen, operator-gated record). This script
is the governed surgical path: text-level substitution only (no YAML
round-trip — every byte outside the 12 source lines and the two
annotation insertions stays identical), full post-conditions, and a
printed SHA-256 for the deliberate loader re-pin
(ConceptGraphSnapshotLoader.CONCEPT_EDGES_SHA256).

WHAT IT DOES NOT TOUCH: edge evidence, provenance fields (tier,
extraction_pass, derivation_method, derivation_notes, upstream,
generated_date), validated_by/validated_date, confidence, status, the
meta.practicals registry (the practical NODES remain first-class official
content, PART_OF-anchored under their spec points), and practicals.yaml.
Untouched provenance fields mean the seed-built provenance line
(t-c11:settled|pass:...|method:...|validated_by:...|date:...) stays
byte-stable, so re-activation resolves the migrated rows as reused rows
(ConceptGraphSeedService.requireSeedEdge contract).

Mapping (verified 1:1 in spec order against the store's own
practicals.yaml spec_point links — identical to the hub mapping):
  4CH1-PR-01 -> 4CH1-1.7C   (solubility of a solid at a set temperature)
  4CH1-PR-02 -> 4CH1-1.13   (paper chromatography, inks/food colourings)
  4CH1-PR-03 -> 4CH1-1.36   (formula of a metal oxide by combustion)
  4CH1-PR-04 -> 4CH1-1.60C  (electrolysis of aqueous solutions)
  4CH1-PR-09 -> 4CH1-3.8    (temperature changes: HCl + NaOH calorimetry)
  4CH1-PR-10 -> 4CH1-3.15   (marble chips surface area / concentration rate)
  4CH1-PR-11 -> 4CH1-3.16   (catalytic decomposition of hydrogen peroxide)
"""
import hashlib
import re
import sys
from pathlib import Path

import yaml

STORE = Path("src/main/resources/concept-graph/concept_edges.yaml")

MAPPING = {
    "4CH1-PR-01": "4CH1-1.7C",
    "4CH1-PR-02": "4CH1-1.13",
    "4CH1-PR-03": "4CH1-1.36",
    "4CH1-PR-04": "4CH1-1.60C",
    "4CH1-PR-09": "4CH1-3.8",
    "4CH1-PR-10": "4CH1-3.15",
    "4CH1-PR-11": "4CH1-3.16",
}

HEADER_NOTE = (
    "# 2026-10-01 practical-endpoint retarget (scripts/retarget_pr_endpoints.py, the\n"
    "# upstream lockstep of the syllabai-hub mirror retarget): the 12 validated\n"
    "# practical prerequisite edges now source at their real spec statements. A c11\n"
    "# generator re-run would re-emit the ad-hoc PR-xx sources and MUST be followed\n"
    "# by this script. Record: docs/PR_ENDPOINT_RETARGET.md."
)

META_NOTE = """  practical_endpoint_retarget: >-
    The 12 HUMAN_VALIDATED practical prerequisite edges previously sourced at the
    ad-hoc practical-node codes (4CH1-PR-01/02/03/04/09/10/11); they now source at
    their real core-practical spec statements (4CH1-1.7C, 4CH1-1.13, 4CH1-1.36,
    4CH1-1.60C, 4CH1-3.8, 4CH1-3.15, 4CH1-3.16) — the same verified 1:1 spec-order
    mapping as the syllabai-hub mirror (fix_pr_edges.py fd05d75, fix_pr_mirror.py),
    so the settled store and its read-model mirror agree again. Edge evidence and
    provenance fields are untouched: the seed-built provenance line stays
    byte-stable and re-activation reuses the migrated rows (V47 moves the seeded
    rows in place). Practical NODES remain first-class official content,
    PART_OF-anchored under their spec statements. Record:
    docs/PR_ENDPOINT_RETARGET.md."""


def provenance_inputs(doc, source_codes):
    """The fields the snapshot loader builds the seed provenance line from, per
    REQUIRES_PREREQUISITE edge sourced at one of `source_codes` — must be
    byte-identical before (PR codes) and after (retargeted spec codes)."""
    out = {}
    for e in doc["edges"]:
        src = e.get("source", "")
        if isinstance(src, str) and src in source_codes and e.get("relation") == "REQUIRES_PREREQUISITE":
            p = e.get("provenance", {})
            out[(src, e["target"])] = (
                p.get("tier"), p.get("extraction_pass"), p.get("derivation_method"),
                p.get("derivation_notes"), p.get("upstream"), p.get("generated_date"),
                e.get("validated_by"), e.get("validated_date"), e.get("validation_status"),
                e.get("confidence"),
            )
    return out


def main() -> int:
    raw = STORE.read_text(encoding="utf-8")
    lines = raw.split("\n")

    # idempotency: already retargeted? (checked before any count expectation)
    if not any(re.match(r"^- source: 4CH1-PR-\d+$", l) for l in lines):
        if "practical_endpoint_retarget" in raw:
            print("already retargeted — nothing to do")
            return 0
        print("ABORT: no PR sources but no retarget annotation either — unexpected state")
        return 1

    pre_doc = yaml.safe_load(raw)
    pre_prov = provenance_inputs(pre_doc, set(MAPPING))
    if len(pre_prov) != 12:
        print(f"ABORT: expected 12 PR-sourced edges, found {len(pre_prov)}")
        return 1
    pre_counts = (
        len(pre_doc["edges"]),
        sum(1 for e in pre_doc["edges"] if e.get("validation_status") == "HUMAN_VALIDATED"),
        sum(1 for e in pre_doc["edges"] if e.get("relation") == "PART_OF"),
    )

    # 1. text-level substitution: only `- source: <PR>` lines whose following
    #    line is the REQUIRES_PREREQUISITE relation of the same edge block
    swapped = {}
    out_lines = []
    for i, l in enumerate(lines):
        m = re.match(r"^- source: (4CH1-PR-\d+)$", l)
        if m and i + 1 < len(lines) and lines[i + 1] == "  relation: REQUIRES_PREREQUISITE":
            code = m.group(1)
            if code not in MAPPING:
                print(f"ABORT: unmapped practical code {code}")
                return 1
            swapped.setdefault(code, []).append(MAPPING[code])
            out_lines.append(f"- source: {MAPPING[code]}")
        else:
            out_lines.append(l)
    print(f"source lines retargeted: {sum(len(v) for v in swapped.values())} (expect 12)")
    if sum(len(v) for v in swapped.values()) != 12:
        print("ABORT: unexpected retarget count")
        return 1

    # 2. header comment note (after the generation-dates line)
    anchor = next(i for i, l in enumerate(out_lines)
                  if l.startswith("# Generation dates pinned per record"))
    out_lines = out_lines[: anchor + 1] + [HEADER_NOTE] + out_lines[anchor + 1 :]

    # 3. machine-readable meta note (after promotion_record, before edges:)
    meta_end = next(i for i, l in enumerate(out_lines)
                    if l.startswith("  promotion_record:"))
    out_lines = out_lines[: meta_end + 1] + [META_NOTE] + out_lines[meta_end + 1 :]

    result = "\n".join(out_lines)

    # ── post-conditions ──────────────────────────────────────────────
    doc = yaml.safe_load(result)
    edges = doc["edges"]
    counts = (
        len(edges),
        sum(1 for e in edges if e.get("validation_status") == "HUMAN_VALIDATED"),
        sum(1 for e in edges if e.get("relation") == "PART_OF"),
    )
    print(f"edge counts (total, human_validated, PART_OF): {pre_counts} -> {counts}")
    if counts != pre_counts:
        print("ABORT: store counts changed")
        return 1

    pr_edge_sources = [e["source"] for e in edges
                       if str(e.get("source", "")).startswith("4CH1-PR-")]
    print(f"remaining PR-sourced edges: {len(pr_edge_sources)} (expect 0)")
    if pr_edge_sources:
        print("ABORT: PR-sourced edges remain")
        return 1

    # every retargeted (source,target) identity present exactly once, and the
    # renamed pre-state identities match exactly
    sp_codes = set(MAPPING.values())
    identities = [(e["source"], e["target"]) for e in edges
                  if e.get("relation") == "REQUIRES_PREREQUISITE"
                  and e["source"] in sp_codes]
    from collections import Counter
    dupes = [k for k, v in Counter(identities).items() if v > 1]
    pre_pairs = {(MAPPING[src], tgt) for (src, tgt) in pre_prov}
    if dupes or len(identities) != 12 or set(identities) != pre_pairs:
        print(f"ABORT: identity mismatch (dupes={dupes} n={len(identities)})")
        return 1

    # the 12 edges' provenance/evidence-bearing fields byte-identical
    post_pairs = provenance_inputs(doc, sp_codes)
    renamed = {(MAPPING[src], tgt): v for (src, tgt), v in pre_prov.items()}
    if post_pairs != renamed:
        diff = {k for k in set(post_pairs) | set(renamed)
                if post_pairs.get(k) != renamed.get(k)}
        print(f"ABORT: provenance drift on {sorted(diff)[:3]}")
        return 1
    print("provenance/evidence fields of the 12 edges: byte-identical")

    # textual residue: the 7 meta.practicals registry entries (the practical
    # NODES remain), this file's own retarget note, and the 1 historical
    # provenance.upstream record — none on an edge source/target line
    residue = [l for l in result.split("\n") if "4CH1-PR-" in l
               and not l.lstrip().startswith("#")]
    bad_residue = [l for l in residue
                   if re.match(r"^\s*-?\s*(source|target):", l)]
    print(f"legitimate PR references remaining (meta registry + history): "
          f"{len(residue) - len(bad_residue)}; on endpoint lines: {len(bad_residue)} (expect 0)")
    if bad_residue:
        print("ABORT: PR reference on an edge endpoint line remains")
        return 1

    new_sha = hashlib.sha256(result.encode("utf-8")).hexdigest()
    STORE.write_text(result, encoding="utf-8")
    print(f"written: {STORE} ({len(raw)} -> {len(result)} bytes)")
    print(f"new CONCEPT_EDGES_SHA256: {new_sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
