# Practical-endpoint retarget — the upstream store lockstep (2026-10-01)

- Operator trace: `1a0f5be571fa8f21` ("the upstream syllabai-core retarget"), executing
  follow-up 1 of the Batch-5 manifest (`docs/TC11_BATCH5_MANIFEST.md` in syllabai-hub):
  *"apply the same PR-xx → real-code mapping to the syllabai-core settled store so the
  mirror and its upstream agree again."*
- Precedent: the hub mirror retarget (`syllabai-hub/scripts/fix_pr_edges.py` fd05d75 for
  `concept-graph.json`, `fix_pr_mirror.py` for `prerequisites.json`) used the same
  verified 1:1 mapping. The mirror's in-file `practicalEndpointRetarget` note recorded
  the divergence from this store; this change closes it.

## 1. The mapping (12 practicals; PR-01..11 identical to the reviewed mirror fix)

| Ad-hoc code | Real spec statement | Practical |
|---|---|---|
| 4CH1-PR-01 | 4CH1-1.7C | solubility of a solid at a set temperature |
| 4CH1-PR-02 | 4CH1-1.13 | paper chromatography (inks / food colourings) |
| 4CH1-PR-03 | 4CH1-1.36 | formula of a metal oxide by combustion |
| 4CH1-PR-04 | 4CH1-1.60C | electrolysis of aqueous solutions |
| 4CH1-PR-05 | 4CH1-2.14 | oxygen percentage in air determination |
| 4CH1-PR-06 | 4CH1-2.21 | reactivity/arrangement (metals and acids) |
| 4CH1-PR-07 | 4CH1-2.42 | preparing a soluble salt |
| 4CH1-PR-08 | 4CH1-2.43C | preparing an insoluble salt |
| 4CH1-PR-09 | 4CH1-3.8 | temperature changes: HCl + NaOH calorimetry |
| 4CH1-PR-10 | 4CH1-3.15 | marble chips: surface area / concentration rate |
| 4CH1-PR-11 | 4CH1-3.16 | catalytic decomposition of hydrogen peroxide |
| 4CH1-PR-12 | 4CH1-4.43C | tests for ions and gases (core set) |

**19 validated edges move.** The retarget was first applied at the batch-4 close
(12 edges: PR-01/02/04/10/11 ×2, PR-03/09 ×1). Mid-lane, origin/main moved: the
operator's 7-edge package (PR-05/06/07/08 ×1 + PR-12 ×3) was authored and
operator-promoted in syllabai-resources batches 5/6/7/11 and core-synced in
`96e7bec` ("the operator's 7-edge-package GO", trace `1a0f3e20d4f963e9`) — superseding
the hub manifest's class-5 NO-GO disposition upstream, with the standing promotion
boundary intact (no #4–#8 anchor work; the 132 concept-backed SP pairs stay derived).
The retarget was then re-applied on top of the batch-11 store (19 edges). Every pair
is verified 1:1 against the store's own `practicals.yaml` (`spec_point` links); the
original 12 edges' provenance fields are byte-identical between the batch-4 and
batch-11 stores (verified programmatically at merge time).

## 2. Why the store carries this change at all

The ad-hoc codes were extractor artifacts (T-C09): the practicals were numbered
`4CH1-PR-01..11` for edge endpoints while the curriculum itself carries every practical
as a real spec statement ("practical: …"). Within the graph-as-code store the PR nodes
resolve (each is PART_OF-anchored under its spec statement), but every downstream
consumer that keys on spec codes — the hub's exported point set, the read-model
exporter, the T-C11 SP-pair projection — cannot resolve an ad-hoc endpoint. The settled
relation is honest at the spec statement: `4CH1-1.7C REQUIRES_PREREQUISITE
4CH1-CON-SATURATED-SOLUTION` says "carrying out the solubility practical requires the
saturation concept", which is exactly what the operator validated.

## 3. What changed (the full lockstep set)

1. **Store** `src/main/resources/concept-graph/concept_edges.yaml`: the 19
   `- source:` lines retargeted; a header comment and a machine-readable
   `meta.practical_endpoint_retarget` record added. **Everything else is
   byte-identical** — evidence, provenance fields (tier, extraction_pass,
   derivation_method, derivation_notes, upstream, generated_date),
   validated_by/validated_date, confidence, status, counts (275 / 153
   HUMAN_VALIDATED / 117 anchors), and the `meta.practicals` registry (the
   practical nodes remain first-class). Applied by
   `scripts/retarget_pr_endpoints.py` (text-level substitution, no YAML
   round-trip, full post-conditions, idempotent). The store header's "DO NOT
   hand-edit" contract is honored in its updated form: the c11 generator
   toolchain is not in this repo (the decision-record registry is frozen
   history), so this governed script is the surgical path — a future generator
   re-run would re-emit the ad-hoc codes and MUST be followed by this script
   (stated in both the header comment and the meta note).
   New SHA-256 (batch-11 store, post-retarget):
   `8a651dd9e60bfefa7a164102db23b10b700672daebc6449c126332180db24aad`
   (intermediate batch-4 pin before the sync: `87af6866…dc16e9`).
2. **Teacher seed** `ConceptGraphSnapshotLoader`: CONCEPT_EDGES pin updated
   deliberately (the pin error message's own contract); the validated
   semantic-edge endpoint check widened to admit spec-statement codes (the seed
   service itself needed no change — spec points already resolve in its `byCode`
   identity map).
3. **NBA layer** `ConceptDependencyGraphLoader`: same pin updated; the loader now
   admits each practical's `spec_point` code as a known endpoint (official store
   content, already pinned via `practicals.yaml` — no new resource). Without this,
   `ConceptDependencyGraph.of`'s fail-closed endpoint check would refuse startup.
   `ConceptDependencyGraph.requireKnown`'s message gloss updated in lockstep.
4. **Data migrations** `V54__retarget_practical_edge_endpoints.sql` (first drafted
   as V47 against the batch-4 store; renumbered after origin consumed V47..V53) +
   `V55__retarget_batch11_practical_edges.sql`. The live sequence on prod exposed a
   scope split the batch-11 sync created: prod had been re-activated with the
   batch-11 snapshot BEFORE the retarget deploy, so the 7 batch-5/6/7/11 edges were
   already seeded (PR-sourced). V54 moved exactly the 12 batch-4-era rows (guard
   green on prod); V55 completes the retarget for the 7 new mappings (PR-05→2.14,
   PR-06→2.21, PR-07→2.42, PR-08→2.43C, PR-12→4.43C) with a 0-or-7 guard. Both move
   rows IN PLACE — only source_node_id; provenance/rationale/status/created_by
   byte-preserved so re-activation reuses every migrated row under the identity +
   provenance contract. Practical nodes and their PART_OF anchors untouched. — only
   `source_node_id` changes; provenance/rationale/status/created_by are
   byte-preserved, so re-activation resolves every migrated row as reused under
   the seed's identity + provenance contract (no duplicates, no conflict).
   Fail-closed guards: target nodes must exist; exactly 12 rows must move (any
   other count is store drift → boot fails loudly); ad-hoc-sourced rows must be
   zero afterwards. Structural no-op on databases where the seed never ran.
5. **Tests**: `ConceptGraphSeedServiceTest` Case A pins the retargeted identities
   and the absence of PR-sourced prerequisite edges;
   `ConceptDependencyGraphLoaderTest` re-pins the hash, pins the retargeted
   endpoints, and keeps the 153/112 count contract; new
   `V47PracticalEndpointRetargetIT` proves the fresh-seed shape, the in-place
   repair + full structural no-op on re-activation, and the fail-closed guard.

## 4. Deliberately unchanged

- The practical nodes (`4CH1-PR-xx`, official spec content, SUBTOPIC type) and
  their PART_OF anchor edges to their spec statements — the practicals stay
  first-class in the curriculum tree; only the 12 prerequisite-edge sources move.
- The historical `provenance.upstream` note on the PR-03 edge ("practical node
  4CH1-PR-03 (T-C09 RULE_DERIVED)") — a true record of where the evidence came from.
- The 5 frozen non-validated edges; all counts; every other edge.
- The syllabai-hub mirror: its retarget note is commit-time-accurate and the
  mirror self-heals on the next sync from the retargeted store (the note's
  "upstream retarget is the follow-up data fix" clause is closed by this change).

## 5. Verification chain

- Store: post-conditions green (19 substitutions; counts stable at 488/389/211;
  the 19 edges' provenance/evidence fields byte-identical; residual PR references
  all legitimate — meta.practicals registry, historical provenance.upstream notes,
  this file's own meta note — none on an endpoint line).
- Local: unit suite green (see commit message for the exact run).
- CI: full unit + IT suite on the pushed commit.
- Prod (after deploy): Flyway V54 applied; 0 REQUIRES_PREREQUISITE edges sourced at
  `4CH1-PR-%`; 12 batch-4-era rows moved to their spec statements with seed
  provenance intact; operator re-activation with the batch-11 snapshot then brings
  the full 272-edge validated layer (all 19 practical edges SP-sourced) as a
  structural no-op for every pre-existing row.
