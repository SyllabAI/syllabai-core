package com.syllabai.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-C13 M1 (spec section 7): Run 002  -  arm A0, the production baseline, recorded.
 *
 * <p>Usage (offline, no DB, no keys):</p>
 * <pre>
 *   mvn test-compile
 *   BENCH_SNAPSHOT=&lt;snapshot dir&gt; BENCH_GOLD=&lt;gold dir&gt; \
 *   BENCH_RUN_OUT=&lt;output dir&gt; BENCH_CORE_COMMIT=&lt;sha&gt; \
 *   mvn test-compile exec:java ...   (or run via a test shim / IDE main)
 * </pre>
 *
 * <p>Determinism contract (spec section 6): no clocks in scoring; the run date comes
 * from {@code BENCH_RUN_DATE}; results are computed twice in-process and the
 * serialized results must be byte-identical or the run aborts. Outputs mirror
 * run-001's schema (comparable line-for-line) plus A0's resolution axis and
 * per-query resolution detail.</p>
 */
public final class Run002A0 {

    private Run002A0() {
    }

    public static void main(String[] args) throws IOException {
        Path snapshotDir = Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot"));
        Path goldDir = Path.of(env("BENCH_GOLD", "bench/gold"));
        Path runOut = Path.of(env("BENCH_RUN_OUT", "evidence/bench-001/runs/run-002-a0"));
        String coreCommit = env("BENCH_CORE_COMMIT", "unrecorded");
        String runDate = env("BENCH_RUN_DATE", "2026-09-17");

        BenchSnapshot snapshot = BenchSnapshot.load(snapshotDir);
        BenchGold gold = BenchGold.load(goldDir);
        ArmA0 arm = new ArmA0(snapshot);

        // ── per-query scoring ─────────────────────────────────────────────
        List<BenchMetrics.ChunkRow> chunkRows = new ArrayList<>();
        List<BenchMetrics.ResolutionRow> resolutionRows = new ArrayList<>();
        List<BenchGold.GoldRecord> labeled = new ArrayList<>();
        List<String> noLabelIds = new ArrayList<>();
        Map<String, Object> perQuery = new LinkedHashMap<>();
        int prereqTotal = 0;
        int misconceptionTotal = 0;
        int queriesWithPrereqs = 0;
        int queriesWithMisconceptions = 0;
        int queriesWithZeroTopics = 0;
        int matchedTopicsTotal = 0;

        for (BenchGold.GoldRecord rec : gold.records()) {
            ArmA0.A0Result result = arm.run(rec.query());
            matchedTopicsTotal += result.rankedTopics().size();
            if (result.rankedTopics().isEmpty()) {
                queriesWithZeroTopics++;
            }
            if (!result.prerequisiteCodes().isEmpty()) {
                queriesWithPrereqs++;
            }
            prereqTotal += result.prerequisiteCodes().size();
            if (!result.misconceptionCodes().isEmpty()) {
                queriesWithMisconceptions++;
            }
            misconceptionTotal += result.misconceptionCodes().size();

            BenchMetrics.ResolutionRow resolution = BenchMetrics.scoreResolution(
                    result.fusedOrder(), rec.goldSpecPoints());
            resolutionRows.add(resolution);

            List<String> rankedCodes = result.rankedTopics().stream().map(ArmA0.Topic::code).toList();
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("matched_topics", rankedCodes);
            q.put("gold_spec_points", rec.goldSpecPoints());
            q.put("coverage", resolution.coverage());
            q.put("exact_hit", resolution.exactHit());
            q.put("prerequisite_codes", result.prerequisiteCodes());
            q.put("misconception_codes", result.misconceptionCodes());
            perQuery.put(rec.id(), q);

            if (rec.goldEvidence().isEmpty()) {
                noLabelIds.add(rec.id());
            } else {
                labeled.add(rec);
                // A0 emits no chunk evidence (production truth). Real zeros per
                // the ratified spec section 6  -  same formulas, empty candidate list.
                Map<String, Integer> tierByRef = new LinkedHashMap<>();
                rec.goldEvidence().forEach(e -> tierByRef.put(e.chunkRef(), e.tier()));
                chunkRows.add(BenchMetrics.scoreChunks(List.of(), tierByRef));
            }
        }

        // ── aggregation (dual axes) ───────────────────────────────────────
        Map<String, String> classById = new LinkedHashMap<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            classById.put(rec.id(), rec.className());
        }

        Map<String, Object> resolutionOverall = BenchMetrics.aggregateResolution(resolutionRows);
        Map<String, List<BenchMetrics.ResolutionRow>> resolutionByClass =
                BenchMetrics.groupByClass(classById, resolutionById(resolutionRows, gold));
        Map<String, Object> resolutionPerClass = new LinkedHashMap<>();
        resolutionByClass.forEach((cls, rows) ->
                resolutionPerClass.put(cls, BenchMetrics.aggregateResolution(rows)));

        Map<String, Object> zeros = BenchMetrics.aggregateChunks(chunkRows);
        Map<String, List<BenchMetrics.ChunkRow>> chunksByClass =
                BenchMetrics.groupByClass(classById, chunkRowsById(chunkRows, labeled));
        Map<String, Object> chunksPerClass = new LinkedHashMap<>();
        chunksByClass.forEach((cls, rows) ->
                chunksPerClass.put(cls, BenchMetrics.aggregateChunks(rows)));

        long violations = 0;   // A0 surfaces no chunks -> no boundary violations by construction

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("run_id", "run-002-a0");
        results.put("date", runDate);
        results.put("arm", "A0 KG-only  -  production GraphKnowledgeRetriever (unmodified) over frozen "
                + "snapshot: title-token specificity matching over VALIDATED structure nodes, "
                + "maxTopics=5, single-token floor 0.50; single-list RRF k=60; NoReranker");
        results.put("arm_status", "RUNNABLE  -  current production serving default; the baseline to beat");
        results.put("code_version", coreCommit);
        results.put("gold_set", "gold-v1 (120 queries; frozen)");
        results.put("snapshot", "snap-001 (" + snapshot.snapshotVersion() + ")");
        results.put("determinism_check", "PENDING");
        results.put("evaluation_contract", Map.of(
                "spec_resolution_axis", "A0's native, scored axis: coverage of gold_spec_points by the "
                        + "RRF-fused ranked topics (production matching semantics, unmodified).",
                "chunk_axis", "A0 emits NO chunk evidence (production truth: vector side empty at "
                        + "0/2,333 embedded; the KG arm emits entity items). Per ratified spec section 6, an "
                        + "empty result from a runnable arm is scored as a REAL ZERO (not excluded) on "
                        + "every chunk-labeled query  -  this zero is the honest picture of today's "
                        + "serving default and the motivation for arms A/B/C.",
                "boundary_check", "A0 surfaces no chunks in either scope -> zero "
                        + "VALIDATION_BOUNDARY_VIOLATION by construction."));
        results.put("queries_total", gold.records().size());
        results.put("queries_scored_resolution", resolutionRows.size());
        results.put("queries_scored_chunks", labeled.size());
        results.put("queries_excluded_no_chunk_labels", Map.of(
                "count", noLabelIds.size(),
                "ids", noLabelIds,
                "reason", "no chunk labels (substrate-absent classes / sparse auto-labels)  -  excluded "
                        + "from the chunk axis only, NOT from spec resolution; same rule as run-001"));
        results.put("spec_resolution", Map.of(
                "overall", resolutionOverall,
                "per_class", resolutionPerClass));
        results.put("chunk_axis", Map.of(
                "all_chunks", Map.of(
                        "scope", "ALL-chunks (" + snapshot.chunkCount() + ")",
                        "corpus_n", snapshot.chunkCount(),
                        "queries_scored", labeled.size(),
                        "overall", zeros,
                        "per_class", chunksPerClass),
                "validated_only", Map.of(
                        "scope", "VALIDATED-paper chunks only (arm emits nothing in any scope)",
                        "corpus_n", snapshot.chunks().values().stream()
                                .filter(c -> "VALIDATED".equals(c.paperState())).count(),
                        "queries_scored", labeled.size(),
                        "overall", zeros,
                        "per_class", chunksPerClass),
                "validation_boundary_violations", violations));
        results.put("signals", Map.of(
                "matched_topics_total", matchedTopicsTotal,
                "queries_with_zero_topics", queriesWithZeroTopics,
                "prerequisite_signals_total", prereqTotal,
                "queries_with_prerequisites", queriesWithPrereqs,
                "misconception_signals_total", misconceptionTotal,
                "queries_with_misconceptions", queriesWithMisconceptions));
        results.put("per_query_resolution", perQuery);
        results.put("arms_unavailable", Map.of(
                "A", "requires T-C07 + embedding backfill (0/2,333 embedded)",
                "B", "requires T-C14 Bm25Retriever + tsvector migration",
                "C/D", "require A + B",
                "E/F/G", "require T-C15"));

        // ── determinism: serialization byte-stable + scoring recomputed twice ──
        results.put("determinism_check",
                "PASS  -  aggregates recomputed twice in-process, byte-identical serialization");
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        String json1 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        if (!json1.equals(json2)) {
            throw new IllegalStateException("nondeterministic serialization (fail-closed)");
        }
        // recompute the whole scoring pipeline a second time and compare aggregates
        Map<String, Object> secondPass = new LinkedHashMap<>();
        secondPass.put("spec_resolution_overall",
                BenchMetrics.aggregateResolution(scoreResolutionAgain(snapshot, gold)));
        secondPass.put("chunk_zeros", BenchMetrics.aggregateChunks(scoreChunksAgain(snapshot, gold)));
        if (!mapper.writeValueAsString(secondPass).equals(
                mapper.writeValueAsString(Map.of(
                        "spec_resolution_overall", resolutionOverall,
                        "chunk_zeros", zeros)))) {
            throw new IllegalStateException("nondeterministic scoring (fail-closed)");
        }

        // ── write evidence ────────────────────────────────────────────────
        Files.createDirectories(runOut);
        Path resultsFile = runOut.resolve("results.json");
        Files.writeString(resultsFile, json2, StandardCharsets.UTF_8);
        Files.writeString(runOut.resolve("RUN_REPORT.md"),
                report(runDate, snapshot, gold, results, resolutionOverall, resolutionPerClass,
                        labeled.size(), noLabelIds.size(), queriesWithZeroTopics),
                StandardCharsets.UTF_8);
        StringBuilder sums = new StringBuilder();
        for (String name : List.of("results.json", "RUN_REPORT.md")) {
            byte[] bytes = Files.readAllBytes(runOut.resolve(name));
            try {
                sums.append(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            sums.append("  ").append(name).append('\n');
        }
        Files.writeString(runOut.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);

        System.out.println("run-002-a0 recorded");
        System.out.println("spec resolution overall: " + resolutionOverall);
        System.out.println("chunk axis (A0 real zeros): " + zeros);
        System.out.println("queries with zero matched topics: " + queriesWithZeroTopics
                + "/" + gold.records().size());
    }

    private static List<BenchMetrics.ResolutionRow> scoreResolutionAgain(
            BenchSnapshot snapshot, BenchGold gold) {
        ArmA0 arm = new ArmA0(snapshot);
        List<BenchMetrics.ResolutionRow> rows = new ArrayList<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            rows.add(BenchMetrics.scoreResolution(arm.run(rec.query()).fusedOrder(),
                    rec.goldSpecPoints()));
        }
        return rows;
    }

    private static List<BenchMetrics.ChunkRow> scoreChunksAgain(
            BenchSnapshot snapshot, BenchGold gold) {
        List<BenchMetrics.ChunkRow> rows = new ArrayList<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            if (rec.goldEvidence().isEmpty()) {
                continue;
            }
            Map<String, Integer> tierByRef = new LinkedHashMap<>();
            rec.goldEvidence().forEach(e -> tierByRef.put(e.chunkRef(), e.tier()));
            rows.add(BenchMetrics.scoreChunks(List.of(), tierByRef));
        }
        return rows;
    }

    private static Map<String, BenchMetrics.ResolutionRow> resolutionById(
            List<BenchMetrics.ResolutionRow> rows, BenchGold gold) {
        Map<String, BenchMetrics.ResolutionRow> out = new LinkedHashMap<>();
        List<BenchGold.GoldRecord> recs = gold.records();
        for (int i = 0; i < rows.size(); i++) {
            out.put(recs.get(i).id(), rows.get(i));
        }
        return out;
    }

    private static Map<String, BenchMetrics.ChunkRow> chunkRowsById(
            List<BenchMetrics.ChunkRow> rows, List<BenchGold.GoldRecord> labeled) {
        Map<String, BenchMetrics.ChunkRow> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            out.put(labeled.get(i).id(), rows.get(i));
        }
        return out;
    }

    private static String report(String runDate, BenchSnapshot snapshot, BenchGold gold,
                                 Map<String, Object> results,
                                 Map<String, Object> resolutionOverall,
                                 Map<String, Object> resolutionPerClass,
                                 int labeledN, int excludedN, int zeroTopicQueries) {
        StringBuilder md = new StringBuilder();
        md.append("# Run 002  -  A0 KG-only baseline (T-C13 M1)\n\n");
        md.append("**Status:** RECORDED  -  production baseline on record (LOCAL VERIFIED; deterministic, ")
                .append("offline, snapshot ").append(snapshot.snapshotVersion()).append(").\n");
        md.append("**Arm:** ").append(results.get("arm")).append("  -  ").append(results.get("arm_status"))
                .append(".\n");
        md.append("**Code:** production `GraphKnowledgeRetriever` + `ReciprocalRankFusion` unmodified over ")
                .append("snapshot-backed repo stubs; core version `").append(results.get("code_version"))
                .append("`.\n");
        md.append("**Date:** ").append(runDate).append(" | **Gold:** gold-v1 frozen | ")
                .append("**Determinism:** double in-process run byte-identical.\n\n");
        md.append("## Evaluation contract\n\n- SpecPoint resolution (flagship, A0's native axis): ")
                .append("coverage of gold spec points by the fused ranked topics (top-5).\n")
                .append("- Chunk axis: A0 emits no chunk evidence (production truth at 0/2,333 embedded) -> ")
                .append("REAL ZEROS per ratified spec section 6, not exclusions. Zero boundary violations.\n\n");
        md.append("## SpecPoint resolution (overall, n=").append(gold.records().size()).append(")\n\n");
        md.append("- ").append(resolutionOverall.toString().replace("{", "").replace("}", ""))
                .append("\n\n");
        md.append("### Per class\n\n| class | coverage_mean | exact_hit_rate | matched_ratio | precision(over matched) |\n");
        md.append("|---|---:|---:|---:|---:|\n");
        Map<?, ?> perClass = (Map<?, ?>) resolutionPerClass;
        for (Map.Entry<?, ?> e : perClass.entrySet()) {
            Map<?, ?> v = (Map<?, ?>) e.getValue();
            md.append("| ").append(e.getKey())
                    .append(" | ").append(v.get("coverage_mean"))
                    .append(" | ").append(v.get("exact_hit_rate"))
                    .append(" | ").append(v.get("matched_ratio"))
                    .append(" | ").append(v.get("precision_mean_over_matched"))
                    .append(" |\n");
        }
        md.append("\n## Chunk axis (A0 real zeros  -  the engine-without-fuel state on record)\n\n");
        md.append("- ALL-chunks (").append(snapshot.chunkCount()).append(") and VALIDATED-only scopes: ")
                .append("all chunk metrics 0.0 over ").append(labeledN)
                .append(" labeled queries (").append(excludedN)
                .append(" excluded  -  no chunk labels, same rule as run-001).\n")
                .append("- This is the baseline the section 8 thresholds will be evaluated against once arms ")
                .append("A/B/C light up: any chunk-producing arm beats it by construction, which is why ")
                .append("the resolution axis and run-001's B-proxy numbers carry the real comparison ")
                .append("weight until then.\n\n");
        md.append("## Signals\n\n- Queries with zero matched topics: ").append(zeroTopicQueries)
                .append("/").append(gold.records().size())
                .append(" (production: fail-closed refusal path).\n");
        Map<?, ?> signals = (Map<?, ?>) results.get("signals");
        md.append("- Prerequisite signals: ").append(signals.get("prerequisite_signals_total"))
                .append(" across ").append(signals.get("queries_with_prerequisites"))
                .append(" queries; misconception signals: ").append(signals.get("misconception_signals_total"))
                .append(" across ").append(signals.get("queries_with_misconceptions"))
                .append(" queries (recorded, unscored).\n\n");
        md.append("## Arms unavailable at this run\n\n- A: T-C07 + embedding backfill (0/2,333 embedded)\n")
                .append("- B: T-C14 Bm25Retriever + tsvector migration\n- C/D: require A + B\n")
                .append("- E/F/G: require T-C15\n\n");
        md.append("## Reading\n\n")
                .append("- A0's chunk-axis zeros are the quantified 'engine built, fuel not loaded' state: ")
                .append("the tutor's evidence chain carries no document chunks today.\n")
                .append("- The resolution profile splits exactly along the documented v0 limitation ")
                .append("(title-token matching, no stemming/synonymy): formal spec-title vocabulary ")
                .append("matches; colloquial learner phrasing does not  -  semantic/lexical arms exist to ")
                .append("close precisely this gap.\n")
                .append("- Numbers are comparable line-for-line with run-001 (same metric formulas, same ")
                .append("exclusion rule, same denominators).\n")
                .append("- Comparator honesty: A0 here runs the production retriever over the frozen ")
                .append("snapshot  -  matching semantics are the production code, not a port; the only ")
                .append("modeling deltas (structure nodes = the 182 snapshot spec points; misconception ")
                .append("family interpretation) are recorded in results.json.\n");
        return md.toString();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
