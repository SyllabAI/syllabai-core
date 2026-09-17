package com.syllabai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.CurriculumScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * T-C13 (spec §7): Run 004 — arm A, the production semantic arm, recorded.
 *
 * <p>Executor: the PRODUCTION vector serving path ({@code ContentRetrievalService}
 * → {@code ChunkVectorRepository.search}: pgvector cosine over V11 vector(768),
 * T-C07 scope predicate; {@code ContentVectorRetriever} cosine floor 0.15,
 * kind-agnostic) against a REAL Postgres migrated with the actual Flyway
 * V1..V28 and loaded with the frozen snap-001 corpus, with the frozen
 * embedding backfill artifact (embed-backfill-snap-001, sessions 92/94)
 * applied and verified fail-closed first. No scorer is reimplemented; the
 * measurement is the production code. Zero API calls at run time — the
 * artifact carries both chunk vectors (RETRIEVAL_DOCUMENT) and gold query
 * vectors (RETRIEVAL_QUERY), so the run replays offline byte-stably
 * (compute-once-freeze-forever).</p>
 *
 * <p>Dual view (honesty rules, §10 ruling 1): the production vector surface
 * predates T-C05 — its scope predicate is T-C07 only, so the SERVED view is
 * the production truth and its boundary audit is expected to surface
 * non-VALIDATED hits as a named finding. The COMPLIANT view (post-hoc
 * VALIDATED-only filter of the served top-20, the run-001 B-proxy
 * {@code validated_only} discipline) is the evaluation view comparable with
 * arm B's compliant scope. Neither view is a promotion claim.</p>
 *
 * <p>Usage (offline, no keys, no API spend; needs one empty Postgres):</p>
 * <pre>
 *   BENCH_JDBC_URL=jdbc:postgresql://localhost:5433/bench \
 *   BENCH_JDBC_USER=bench BENCH_JDBC_PASSWORD=bench \
 *   BENCH_SNAPSHOT=&lt;snapshot dir&gt; BENCH_GOLD=&lt;gold dir&gt; \
 *   BENCH_EMBED_ARTIFACT=&lt;embed-backfill-snap-001 dir&gt; \
 *   BENCH_RUN_OUT=&lt;output dir&gt; BENCH_CORE_COMMIT=&lt;sha&gt; \
 *   [BENCH_RUN003B_RESULTS=&lt;run-003-b results.json&gt;] \
 *   java -cp target/test-classes:target/classes:&lt;deps&gt; com.syllabai.bench.Run004A
 * </pre>
 *
 * <p>Determinism contract (spec §6): no clocks in scoring (BENCH_RUN_DATE pins
 * the date), the artifact is verified against the exact frozen inputs before
 * anything runs, scoring is recomputed twice in-process (both views) and the
 * serialized aggregates must be byte-identical or the run aborts.</p>
 */
public final class Run004A {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Run004A() {
    }

    public static void main(String[] args) throws Exception {
        Path snapshotDir = Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot"));
        Path goldDir = Path.of(env("BENCH_GOLD", "bench/inputs/gold"));
        Path artifactDir = Path.of(required("BENCH_EMBED_ARTIFACT"));
        Path runOut = Path.of(env("BENCH_RUN_OUT", "evidence/bench-001/runs/run-004-a"));
        Path run003b = Path.of(env("BENCH_RUN003B_RESULTS",
                "evidence/bench-001/runs/run-003-b/results.json"));
        String coreCommit = env("BENCH_CORE_COMMIT", "unrecorded");
        String runDate = env("BENCH_RUN_DATE", "2026-09-17");

        String url = required("BENCH_JDBC_URL");
        String user = required("BENCH_JDBC_USER");
        String pass = required("BENCH_JDBC_PASSWORD");

        BenchSnapshot snapshot = BenchSnapshot.load(snapshotDir);
        BenchGold gold = BenchGold.load(goldDir);

        Flyway.configure().dataSource(url, user, pass).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Result result = run(jdbc, snapshotDir, snapshot, gold, goldDir, artifactDir, runOut,
                coreCommit, runDate, run003b);
        System.out.println("run-004-a recorded");
        System.out.println("A served overall: " + result.servedOverall());
        System.out.println("A compliant overall: " + result.compliantOverall());
        System.out.println("boundary findings (served): " + result.violations()
                + " | zero-result queries: " + result.zeroResultQueries()
                + " | compliant-starved: " + result.compliantStarved());
    }

    record Result(String status, int corpusChunks, int appliedVectors, int queries,
                  Map<String, Object> servedOverall, Map<String, Object> compliantOverall,
                  int violations, int zeroResultQueries, int compliantStarved) {
    }

    /** Injectable core — the IT drives this over a tiny seeded corpus (no snapshot dir). */
    static Result run(JdbcTemplate jdbc, Path snapshotDir, BenchSnapshot snapshot, BenchGold gold,
                      Path goldDir, Path artifactDir, Path runOut, String coreCommit,
                      String runDate, Path run003bResults) throws Exception {

        // ── 0. verify the frozen artifact against the exact frozen inputs ────
        log("verifying artifact checksums (fail-closed)");
        verifyArtifact(artifactDir, snapshotDir, goldDir, gold, snapshot, true);

        // ── 1. real Postgres, real migrations, frozen corpus ─────────────────
        Run003B.SnapshotLoad load = null;
        int dbChunks = jdbc.queryForObject("select count(*) from document_chunks", Integer.class);
        if (snapshot != null) {
            log("loading snap-001 corpus through the production loader");
            load = Run003B.loadSnapshot(jdbc, snapshot);
            dbChunks = jdbc.queryForObject("select count(*) from document_chunks", Integer.class);
            if (dbChunks != snapshot.chunkCount()) {
                throw new IllegalStateException("document_chunks " + dbChunks
                        + " != snapshot chunks " + snapshot.chunkCount() + " (fail-closed)");
            }
        } else if (dbChunks == 0) {
            throw new IllegalStateException("empty document_chunks and no snapshot provided (fail-closed)");
        }
        CurriculumScope scope = load != null ? load.scope() : benchScope(jdbc);

        // ── 2. apply the artifact's chunk vectors (bit-exact replay) ─────────
        log("applying frozen chunk vectors");
        int applied = applyChunkVectors(jdbc, artifactDir, snapshot);
        assertStoredState(jdbc, artifactDir);

        // ── 3. the production arm with frozen query vectors ──────────────────
        Map<String, String> paperStateByDocumentId = snapshot != null
                ? paperStateByDocumentId(snapshot)
                : paperStateFromDb(jdbc);
        ArmA arm = new ArmA(ArmA.productionRetriever(new ArmA.JdbcTemplateHolder(jdbc),
                frozenQueryProvider(artifactDir, gold)), scope, paperStateByDocumentId);

        // ── 4. per-query scoring: served view (production truth) + compliant view
        List<BenchMetrics.ChunkRow> servedRows = new ArrayList<>();
        List<BenchMetrics.ChunkRow> compliantRows = new ArrayList<>();
        List<BenchGold.GoldRecord> labeled = new ArrayList<>();
        List<String> noLabelIds = new ArrayList<>();
        Map<String, Object> perQueryServed = new LinkedHashMap<>();
        Map<String, Object> perQueryCompliant = new LinkedHashMap<>();
        int violations = 0;
        List<String> violationRefs = new ArrayList<>();
        int zeroResultQueries = 0;
        int compliantStarved = 0;

        for (BenchGold.GoldRecord rec : gold.records()) {
            ArmA.AResult served = arm.run(rec.query(), 20);
            violations += served.boundaryViolations();
            violationRefs.addAll(served.violationRefs());
            if (served.rankedRefs().isEmpty()) {
                zeroResultQueries++;
            }
            ArmA.AResult compliant = ArmA.compliantView(served, paperStateByDocumentId);
            if (!served.rankedRefs().isEmpty() && compliant.rankedRefs().isEmpty()) {
                compliantStarved++;
            }
            if (rec.goldEvidence().isEmpty()) {
                noLabelIds.add(rec.id());
                continue;
            }
            labeled.add(rec);
            Map<String, Integer> tierByRef = new LinkedHashMap<>();
            rec.goldEvidence().forEach(e -> tierByRef.put(e.chunkRef(), e.tier()));
            servedRows.add(BenchMetrics.scoreChunks(served.rankedRefs(), tierByRef));
            compliantRows.add(BenchMetrics.scoreChunks(compliant.rankedRefs(), tierByRef));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("ranked_refs", served.rankedRefs());
            s.put("scores", served.scores());
            s.put("gold_spec_points", rec.goldSpecPoints());
            perQueryServed.put(rec.id(), s);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("ranked_refs", compliant.rankedRefs());
            c.put("scores", compliant.scores());
            perQueryCompliant.put(rec.id(), c);
        }

        // ── 5. aggregation (both views, dual-denominator context) ────────────
        Map<String, String> classById = new LinkedHashMap<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            classById.put(rec.id(), rec.className());
        }
        Map<String, Object> servedOverall = BenchMetrics.aggregateChunks(servedRows);
        Map<String, Object> compliantOverall = BenchMetrics.aggregateChunks(compliantRows);
        Map<String, List<BenchMetrics.ChunkRow>> servedByClass = BenchMetrics.groupByClass(
                classById, chunkRowsById(servedRows, labeled));
        Map<String, List<BenchMetrics.ChunkRow>> compliantByClass = BenchMetrics.groupByClass(
                classById, chunkRowsById(compliantRows, labeled));
        Map<String, Object> servedPerClass = new LinkedHashMap<>();
        servedByClass.forEach((cls, rows) -> servedPerClass.put(cls, BenchMetrics.aggregateChunks(rows)));
        Map<String, Object> compliantPerClass = new LinkedHashMap<>();
        compliantByClass.forEach((cls, rows) -> compliantPerClass.put(cls, BenchMetrics.aggregateChunks(rows)));

        long validatedCorpus = paperStateByDocumentId.values().stream()
                .filter("VALIDATED"::equals).count();

        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        Map<String, Object> counts = new LinkedHashMap<>();
        manifest.path("counts").fields()
                .forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt()));
        Map<String, Object> prev003 = run003bResults != null && Files.exists(run003bResults)
                ? readJson(run003bResults) : Map.of();
        Map<String, Object> context003 = new LinkedHashMap<>();
        if (!prev003.isEmpty()) {
            context003.put("note", "arm B (production lexical, VALIDATED-served scope) from the "
                    + "run-003-b record; same frozen gold, same formulas");
            Object bOverall = Run003BHelpers.extractOverall(prev003);
            if (bOverall != null) {
                context003.put("b_overall", bOverall);
            }
            Object bCorpusN = Run003BHelpers.extractCorpusN(prev003);
            if (bCorpusN != null) {
                context003.put("b_corpus_n", bCorpusN);
            }
        }

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("run_id", "run-004-a");
        results.put("date", runDate);
        results.put("arm", "A semantic — PRODUCTION vector serving path (ContentRetrievalService → "
                + "ChunkVectorRepository.search: pgvector 1-(embedding <=> q) cosine over V11 vector(768), "
                + "T-C07 scope EXISTS predicate; ContentVectorRetriever cosine floor 0.15, kind-agnostic) "
                + "— chunk+query vectors replayed from the frozen artifact embed-backfill-snap-001, "
                + "zero API calls at run time, NoReranker");
        results.put("arm_status", "RUNNABLE — frozen backfill artifact applied (" + applied + "/"
                + dbChunks + " embedded); benchmark arm only, NOT a production serving default; the "
                + "production embedding default is still the RETIRED text-embedding-004 (registered "
                + "repair, separate slice)");
        results.put("code_version", coreCommit);
        results.put("gold_set", "gold-v1 (120 queries; frozen)");
        results.put("snapshot", snapshot != null ? "snap-001 (" + snapshot.snapshotVersion() + ")"
                : "pre-loaded bench corpus (no snapshot dir)");
        results.put("executor", "production code over real Postgres migrated V1..V28 (Flyway), corpus "
                + "loaded from the frozen snapshot; chunk vectors applied from the checksummed "
                + "compute-once-freeze-forever artifact and query vectors served from it through the "
                + "production EmbeddingProvider port; no scorer reimplemented, no retrieval SQL changed");
        results.put("embedding_artifact", Map.of(
                "run_id", manifest.path("run_id").asText(),
                "model", manifest.path("model").asText(),
                "dimension", manifest.path("dimension").asInt(),
                "counts", counts,
                "backfill_core_commit", manifest.path("core_commit").asText(),
                "backfill_run_date", manifest.path("run_date").asText(),
                "sha256_echo", EmbedBackfill.Manifests.filesSha256(artifactDir)));
        results.put("evaluation_contract", Map.of(
                "chunk_axis", "Recall@5/10/20, MRR (first tier-2 hit in top-20), nDCG@10 (2/1/0 tiers), "
                        + "evidence precision@10 and FP@10 over the fixed top-10 denominator — formulas "
                        + "identical to run-001/run-002/run-003 (BenchMetrics, pinned).",
                "serving_scope", "the production vector surface enforces T-C07 curriculum scoping but "
                        + "predates T-C05: the SERVED view is the production truth over the T-C07-scoped "
                        + "embedded corpus; the COMPLIANT view is the post-hoc VALIDATED-only filter of the "
                        + "served top-20 (run-001 B-proxy validated_only discipline) — an evaluation view "
                        + "comparable with arm B's compliant scope, NOT a serving simulation (a compliant "
                        + "vector surface would re-rank within the compliant corpus and is not implemented; "
                        + "registered as follow-up)",
                "cosine_floor", "hits below the production ContentVectorRetriever MIN_COSINE 0.15 are "
                        + "dropped before ranking (production truth) — empty result lists are honest zeros",
                "spec_resolution_axis", "NOT SCOREABLE for arm A: the snapshot carries ZERO "
                        + "HUMAN_VALIDATED chunk→spec mapping rows (concept_attachments = 0, the "
                        + "T-C06/F-168 mapping substrate is pending), so a resolution number would be "
                        + "fabrication; recorded as a named data gap, not a zero, not an exclusion",
                "boundary_check", "every served hit audited against the loader's paper-state map; any "
                        + "non-VALIDATED hit is a VALIDATION_BOUNDARY_VIOLATION finding — on this "
                        + "production surface it is EXPECTED (the T-C05 predicate exists on the lexical "
                        + "serving-eligible surface only) and is recorded as the run's named finding, "
                        + "never silently patched"));
        results.put("queries_total", gold.records().size());
        results.put("queries_scored_chunks", labeled.size());
        results.put("queries_excluded_no_chunk_labels", Map.of(
                "count", noLabelIds.size(),
                "ids", noLabelIds,
                "reason", "no chunk labels (substrate-absent classes / sparse auto-labels) — excluded "
                        + "from the chunk axis only; same rule as run-001/run-002/run-003"));
        results.put("queries_with_zero_results", zeroResultQueries);
        results.put("queries_compliant_starved", compliantStarved);
        results.put("chunk_axis", Map.of(
                "served_view", Map.of(
                        "scope", "T-C07-scoped embedded corpus (production vector surface as-is)",
                        "corpus_n", dbChunks,
                        "queries_scored", labeled.size(),
                        "overall", servedOverall,
                        "per_class", servedPerClass),
                "compliant_view", Map.of(
                        "scope", "post-hoc VALIDATED-only filter of the served top-20 (evaluation view)",
                        "corpus_n", validatedCorpus,
                        "queries_scored", labeled.size(),
                        "overall", compliantOverall,
                        "per_class", compliantPerClass),
                "validation_boundary_violations", violations,
                "violation_refs", violationRefs));
        results.put("arms_registry", Map.of(
                "A0", "RUNNABLE — recorded in run-002-a0 (production baseline; chunk axis = real zeros, "
                        + "resolution axis on record)",
                "B", "RUNNABLE — recorded in run-003-b (production lexical arm)",
                "B-proxy", "RECORDED (run-001) — harness-internal probe; never citable for promotion",
                "A", "RUNNABLE — this run (production semantic arm, first recorded run)",
                "C/D", "UNAVAILABLE — require orchestration of A+B (the retrieval fabric has port + "
                        + "adapters but ZERO consumers; the orchestrator is the registered gap)",
                "E/F/G", "UNAVAILABLE — require T-C15",
                "H1", "UNAVAILABLE — hierarchical filtering over C",
                "H2", "UNAVAILABLE — T-C06 chunking contract",
                "H3", "UNAVAILABLE — HyPE over A",
                "I", "UNAVAILABLE — KG expansion + best-of"));
        results.put("per_query_chunks", perQueryServed);
        results.put("per_query_compliant", perQueryCompliant);
        if (!context003.isEmpty()) {
            results.put("context_run_003_b", context003);
        }

        // ── 6. determinism: recomputed scoring + byte-stable serialization ───
        List<BenchMetrics.ChunkRow> secondServed = new ArrayList<>();
        List<BenchMetrics.ChunkRow> secondCompliant = new ArrayList<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            if (rec.goldEvidence().isEmpty()) {
                continue;
            }
            ArmA.AResult again = arm.run(rec.query(), 20);
            Map<String, Integer> tiers = new LinkedHashMap<>();
            rec.goldEvidence().forEach(e -> tiers.put(e.chunkRef(), e.tier()));
            secondServed.add(BenchMetrics.scoreChunks(again.rankedRefs(), tiers));
            ArmA.AResult againCompliant = ArmA.compliantView(again, paperStateByDocumentId);
            secondCompliant.add(BenchMetrics.scoreChunks(againCompliant.rankedRefs(), tiers));
        }
        ObjectMapper stable = new ObjectMapper();
        stable.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        checkEquals(stable.writeValueAsString(BenchMetrics.aggregateChunks(secondServed)),
                stable.writeValueAsString(servedOverall), "served view");
        checkEquals(stable.writeValueAsString(BenchMetrics.aggregateChunks(secondCompliant)),
                stable.writeValueAsString(compliantOverall), "compliant view");
        results.put("determinism_check", "PASS — scoring recomputed twice in-process (second full "
                + "retrieval pass), both views' aggregates byte-identical; serialization byte-stable");

        // ── 7. write evidence (results.json + RUN_REPORT.md + SHA256SUMS) ────
        Files.createDirectories(runOut);
        String pretty = stable.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        Files.writeString(runOut.resolve("results.json"), pretty, StandardCharsets.UTF_8);
        Files.writeString(runOut.resolve("RUN_REPORT.md"),
                report(runDate, snapshot, results, servedOverall, compliantOverall,
                        servedPerClass, compliantPerClass, labeled.size(), noLabelIds.size(),
                        zeroResultQueries, compliantStarved, violations, dbChunks,
                        validatedCorpus, context003, manifest),
                StandardCharsets.UTF_8);
        StringBuilder sums = new StringBuilder();
        for (String name : List.of("results.json", "RUN_REPORT.md")) {
            byte[] bytes = Files.readAllBytes(runOut.resolve(name));
            sums.append(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
            sums.append("  ").append(name).append('\n');
        }
        Files.writeString(runOut.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);

        return new Result("RECORDED", dbChunks, applied, gold.records().size(),
                servedOverall, compliantOverall, violations, zeroResultQueries, compliantStarved);
    }

    // ── artifact verification (fail-closed) ───────────────────────────────────

    static void verifyArtifact(Path artifactDir, Path snapshotDir, Path goldDir, BenchGold gold,
                               BenchSnapshot snapshot, boolean requireComplete) throws Exception {
        Path sums = artifactDir.resolve("SHA256SUMS");
        if (!Files.exists(sums)) {
            throw new IllegalStateException("artifact SHA256SUMS missing (fail-closed)");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+", 2);
            String name = parts[1].trim();
            seen.add(name);
            Path f = artifactDir.resolve(name);
            if (!Files.exists(f)) {
                throw new IllegalStateException("artifact file missing: " + name + " (fail-closed)");
            }
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
            if (!actual.equalsIgnoreCase(parts[0])) {
                throw new IllegalStateException("artifact file " + name + " SHA-256 mismatch (fail-closed)");
            }
        }
        List<String> requiredNames = new ArrayList<>(List.of("embeddings_chunks.jsonl", "manifest.json"));
        if (requireComplete) {
            requiredNames.add("embeddings_queries.jsonl");
        }
        for (String required : requiredNames) {
            if (!seen.contains(required)) {
                throw new IllegalStateException("artifact missing " + required + " (fail-closed)");
            }
        }

        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        if (requireComplete && manifest.path("pending_after").asInt(0) != 0) {
            throw new IllegalStateException("artifact carries pending chunks — backfill must COMPLETE first");
        }
        int dim = manifest.path("dimension").asInt(-1);
        if (dim != 768) {
            throw new IllegalStateException("artifact dimension " + dim + " != vector(768) column (fail-closed)");
        }
        String taskType = manifest.path("task_types").path("chunks").asText();
        if (!"RETRIEVAL_DOCUMENT".equals(taskType)) {
            throw new IllegalStateException("artifact chunk task type " + taskType + " unexpected (fail-closed)");
        }

        JsonNode snapEcho = manifest.path("snapshot_files_sha256");
        if (snapshotDir != null) {
            if (!snapEcho.isObject() || snapEcho.isEmpty()) {
                throw new IllegalStateException("artifact lacks snapshot provenance (fail-closed)");
            }
            Map<String, String> local = EmbedBackfill.Manifests.filesSha256(snapshotDir);
            Map<String, String> echoed = new LinkedHashMap<>();
            snapEcho.fieldNames().forEachRemaining(n -> echoed.put(n, snapEcho.path(n).asText()));
            if (!local.equals(echoed)) {
                throw new IllegalStateException("artifact snapshot provenance != local snapshot checksums "
                        + "(fail-closed — refusing to mix corpora)");
            }
            if (snapshot != null && !snapshot.snapshotVersion()
                    .equals(manifest.path("snapshot_version").asText())) {
                throw new IllegalStateException("artifact snapshot_version mismatch (fail-closed)");
            }
        } else if (snapEcho.isObject() && !snapEcho.isEmpty()) {
            throw new IllegalStateException("artifact pins a snapshot this replay does not load (fail-closed)");
        }
        JsonNode goldEcho = manifest.path("gold_files_sha256");
        if (gold != null) {
            if (!goldEcho.isObject() || goldEcho.isEmpty()) {
                throw new IllegalStateException("artifact lacks gold provenance (fail-closed)");
            }
            Map<String, String> localGold = EmbedBackfill.Manifests.filesSha256(goldDir);
            Map<String, String> echoedGold = new LinkedHashMap<>();
            goldEcho.fieldNames().forEachRemaining(n -> echoedGold.put(n, goldEcho.path(n).asText()));
            if (!localGold.equals(echoedGold)) {
                throw new IllegalStateException("artifact gold provenance != local gold checksums (fail-closed)");
            }
        }
    }

    // ── artifact application (bit-exact replay into the production store) ────

    static int applyChunkVectors(JdbcTemplate jdbc, Path artifactDir, BenchSnapshot snapshot)
            throws Exception {
        // Run-004-A replay path: the frozen artifact must cover the whole snapshot.
        return applyChunkVectors(jdbc, artifactDir, snapshot, true);
    }

    /**
     * {@code requireComplete=false} is the EmbedBackfill preload/resume path:
     * partial artifacts are its whole point (session 92), so coverage of the
     * full snapshot is NOT asserted — every row is still fail-closed verified
     * against the snapshot (ref membership, chunk_id, deterministic id).
     */
    static int applyChunkVectors(JdbcTemplate jdbc, Path artifactDir, BenchSnapshot snapshot,
                                 boolean requireComplete)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var mapType = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class);
        ChunkVectorRepository vectors = new ChunkVectorRepository(jdbc);
        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        String model = manifest.path("model").asText();

        Set<String> artifactRefs = new LinkedHashSet<>();
        int applied = 0;
        List<String> lines = Files.readAllLines(artifactDir.resolve("embeddings_chunks.jsonl"),
                StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> row = mapper.readValue(line, mapType);
            String ref = String.valueOf(row.get("ref"));
            String chunkId = String.valueOf(row.get("chunk_id"));
            String rowModel = String.valueOf(row.get("model"));
            if (!model.equals(rowModel)) {
                throw new IllegalStateException("artifact row model " + rowModel + " != manifest " + model);
            }
            @SuppressWarnings("unchecked")
            List<Number> raw = (List<Number>) row.get("v");
            float[] v = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                v[i] = raw.get(i).floatValue();
            }
            if (v.length != 768) {
                throw new IllegalStateException("artifact vector for " + ref + " has " + v.length + " dims");
            }
            if (snapshot != null && !snapshot.chunks().containsKey(ref)) {
                throw new IllegalStateException("artifact ref not in snapshot: " + ref + " (fail-closed)");
            }
            int colon = ref.lastIndexOf(':');
            String documentId = ref.substring(0, colon);
            int chunkIndex = Integer.parseInt(ref.substring(colon + 1));
            UUID rowId = jdbc.queryForObject("""
                    select c.id from document_chunks c
                    join documents d on d.id = c.document_row_id
                    where d.document_id = ? and c.chunk_index = ?
                    """, (rs, i) -> rs.getObject("id", UUID.class), documentId, chunkIndex);
            if (!chunkId.equals(rowId.toString())) {
                throw new IllegalStateException("artifact chunk_id " + chunkId + " != bench row " + rowId
                        + " for " + ref + " (fail-closed — mixed corpus protection)");
            }
            if (snapshot != null) {
                UUID deterministic = UUID.nameUUIDFromBytes(
                        ("bench-chunk|" + ref).getBytes(StandardCharsets.UTF_8));
                if (!deterministic.equals(rowId)) {
                    throw new IllegalStateException("bench row id for " + ref + " is not the loader's "
                            + "deterministic id (fail-closed)");
                }
            }
            if (vectors.storeEmbedding(rowId, v, model) != 1) {
                throw new IllegalStateException("storeEmbedding updated 0 rows for " + ref);
            }
            artifactRefs.add(ref);
            applied++;
            if (applied % 500 == 0) {
                log("applied " + applied + " vectors");
            }
        }
        if (requireComplete && snapshot != null && artifactRefs.size() != snapshot.chunkCount()) {
            throw new IllegalStateException("artifact carries " + artifactRefs.size() + " refs, snapshot has "
                    + snapshot.chunkCount() + " (fail-closed)");
        }
        return applied;
    }

    static void assertStoredState(JdbcTemplate jdbc, Path artifactDir) throws Exception {
        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        String model = manifest.path("model").asText();
        int expected = manifest.path("counts").path("chunks_stored_total").asInt(-1);
        Integer pending = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is null", Integer.class);
        Integer embedded = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is not null", Integer.class);
        List<String> models = jdbc.queryForList(
                "select distinct embedding_model from document_chunks where embedding is not null", String.class);
        if (pending != 0) {
            throw new IllegalStateException(pending + " chunks still pending after apply (fail-closed)");
        }
        if (expected >= 0 && embedded != expected) {
            throw new IllegalStateException("embedded rows " + embedded + " != artifact chunks_stored_total "
                    + expected + " (fail-closed)");
        }
        if (models.size() != 1 || !model.equals(models.get(0))) {
            throw new IllegalStateException("embedding models in DB " + models + " != artifact model "
                    + model + " (fail-closed — single-model index rule)");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static EmbeddingProvider frozenQueryProvider(Path artifactDir, BenchGold gold) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var mapType = mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class);
        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        String model = manifest.path("model").asText();
        int dim = manifest.path("dimension").asInt();
        Map<String, float[]> byQid = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(artifactDir.resolve("embeddings_queries.jsonl"),
                StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> row = mapper.readValue(line, mapType);
            String qid = String.valueOf(row.get("qid"));
            if (!model.equals(String.valueOf(row.get("model")))) {
                throw new IllegalStateException("query artifact model mismatch for " + qid);
            }
            @SuppressWarnings("unchecked")
            List<Number> raw = (List<Number>) row.get("v");
            float[] v = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                v[i] = raw.get(i).floatValue();
            }
            if (v.length != dim) {
                throw new IllegalStateException("query vector for " + qid + " has " + v.length + " dims");
            }
            byQid.put(qid, v);
        }
        Map<String, float[]> byStrippedQuery = new LinkedHashMap<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            float[] v = byQid.get(rec.id());
            if (v == null) {
                throw new IllegalStateException("artifact lacks a frozen vector for gold query " + rec.id());
            }
            byStrippedQuery.put(rec.query().strip(), v);
        }
        if (byQid.size() != gold.records().size()) {
            throw new IllegalStateException("artifact query vectors " + byQid.size() + " != gold records "
                    + gold.records().size() + " (fail-closed)");
        }
        return new ArmA.FrozenQueryVectors(model, dim, byStrippedQuery);
    }

    static Map<String, String> paperStateByDocumentId(BenchSnapshot snapshot) {
        Map<String, String> states = new LinkedHashMap<>();
        for (BenchSnapshot.ChunkRef c : snapshot.chunks().values()) {
            String docId = c.reference().substring(0, c.reference().lastIndexOf(':'));
            states.putIfAbsent(docId, c.paperState());
        }
        return states;
    }

    private static Map<String, String> paperStateFromDb(JdbcTemplate jdbc) {
        Map<String, String> states = new LinkedHashMap<>();
        jdbc.query("""
                select d.document_id as doc, p.validation_state as state
                from documents d
                join exam_papers p on p.question_paper_document_id = d.document_id
                    or p.mark_scheme_document_id = d.document_id
                """, rs -> {
            states.putIfAbsent(rs.getString("doc"), rs.getString("state"));
        });
        if (states.isEmpty()) {
            throw new IllegalStateException("no paper-linked documents for the boundary audit (fail-closed)");
        }
        return states;
    }

    /**
     * Scope fallback for a pre-loaded bench DB (IT path) — the curriculum
     * version owning the corpus's papers. NOT a count over curriculum_versions:
     * V6 seeds a baseline IAL row, so every migrated DB carries at least two
     * versions and only the papers-owning one is the bench scope (CI run
     * 35211795632 found this; the loader path is unaffected — it derives the
     * deterministic scope id itself).
     */
    private static CurriculumScope benchScope(JdbcTemplate jdbc) {
        List<UUID> ids = jdbc.queryForList(
                "select distinct s.curriculum_version_id "
                        + "from exam_papers p join subjects s on s.id = p.subject_id",
                UUID.class);
        if (ids.size() != 1) {
            throw new IllegalStateException("expected exactly one curriculum version owning the bench papers, "
                    + "found " + ids.size() + " (fail-closed)");
        }
        return new CurriculumScope(ids.get(0), "BENCH", Set.of());
    }

    private static Map<String, BenchMetrics.ChunkRow> chunkRowsById(
            List<BenchMetrics.ChunkRow> rows, List<BenchGold.GoldRecord> labeled) {
        Map<String, BenchMetrics.ChunkRow> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            out.put(labeled.get(i).id(), rows.get(i));
        }
        return out;
    }

    private static void checkEquals(String a, String b, String what) {
        if (!a.equals(b)) {
            throw new IllegalStateException("nondeterministic scoring (fail-closed) in " + what
                    + ": " + a + " != " + b);
        }
    }

    private static Map<String, Object> readJson(Path path) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(path.toFile(),
                    mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Context extractors for the run-003-b record (kept here to avoid touching the recorded harness). */
    @SuppressWarnings("unchecked")
    private static final class Run003BHelpers {
        static Object extractOverall(Map<String, Object> prev003) {
            Object axis = prev003.get("chunk_axis");
            if (axis instanceof Map<?, ?> m) {
                Object vo = ((Map<String, Object>) m).get("validated_only_served");
                if (vo instanceof Map<?, ?> vo2) {
                    return ((Map<String, Object>) vo2).get("overall");
                }
            }
            return null;
        }

        static Object extractCorpusN(Map<String, Object> prev003) {
            Object axis = prev003.get("chunk_axis");
            if (axis instanceof Map<?, ?> m) {
                Object vo = ((Map<String, Object>) m).get("validated_only_served");
                if (vo instanceof Map<?, ?> vo2) {
                    return ((Map<String, Object>) vo2).get("corpus_n");
                }
            }
            return null;
        }
    }

    // ── RUN_REPORT.md ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String report(String runDate, BenchSnapshot snapshot, Map<String, Object> results,
                                 Map<String, Object> servedOverall, Map<String, Object> compliantOverall,
                                 Map<String, Object> servedPerClass, Map<String, Object> compliantPerClass,
                                 int labeledN, int excludedN, int zeroResultQueries, int compliantStarved,
                                 int violations, int corpusN, long validatedCorpus,
                                 Map<String, Object> context003, JsonNode manifest) {
        StringBuilder md = new StringBuilder();
        md.append("# Run 004 — A semantic arm, first recorded run (embedding backfill + arm A)\n\n");
        md.append("**Status:** RECORDED — production semantic arm on record (LOCAL VERIFIED; deterministic, ")
                .append("offline replay, zero API calls; snapshot ")
                .append(snapshot != null ? snapshot.snapshotVersion() : "pre-loaded bench corpus").append(").\n");
        md.append("**Arm:** ").append(results.get("arm")).append("\n");
        md.append("**Executor:** ").append(results.get("executor")).append(" — code `")
                .append(results.get("code_version")).append("`.\n");
        md.append("**Date:** ").append(runDate).append(" | **Gold:** gold-v1 frozen | ")
                .append("**Determinism:** double retrieval pass, byte-identical aggregates (both views).\n\n");

        md.append("## Embedding backfill provenance\n\n")
                .append("- Artifact `").append(manifest.path("run_id").asText()).append("`: model `")
                .append(manifest.path("model").asText()).append("` @ ").append(manifest.path("dimension").asInt())
                .append(" dims, task types RETRIEVAL_DOCUMENT (chunks) / RETRIEVAL_QUERY (queries); ")
                .append("computed through the PRODUCTION EmbeddingProvider bean path and stored through the ")
                .append("real ChunkVectorRepository.storeEmbedding, then frozen (SHA256SUMS) — ")
                .append("compute-once-freeze-forever. Verified fail-closed against this run's frozen inputs ")
                .append("before anything ran.\n")
                .append("- The codebase default `text-embedding-004` is RETIRED (404, probed 2026-09-17 with a ")
                .append("valid key); the artifact used the successor `gemini-embedding-001` at explicit ")
                .append("outputDimensionality=768 (vector(768)-compatible). The production default repair is a ")
                .append("separately registered row, not part of this benchmark slice.\n")
                .append("- Artifact SHA-256 echo: results.json `embedding_artifact.sha256_echo`.\n\n");

        md.append("## Overall (chunk axis, n=").append(labeledN).append(" labeled queries)\n\n");
        md.append("- **A served (production vector surface, T-C07-scoped, ").append(corpusN)
                .append(" embedded chunks):** ").append(fmt(servedOverall)).append("\n");
        md.append("- **A compliant view (post-hoc VALIDATED-only filter of the served top-20, ")
                .append(validatedCorpus).append(" reachable chunks):** ").append(fmt(compliantOverall))
                .append("\n");
        Object bOverall = context003.get("b_overall");
        if (bOverall instanceof Map) {
            md.append("- **B (run-003-b, production lexical, VALIDATED-served scope):** ")
                    .append(fmt((Map<String, Object>) bOverall)).append("\n");
        }
        md.append("- **A0 chunk axis (run-002):** all zeros by production truth (0/2,333 embedded at the time) ")
                .append("— superseded by this run once the backfill artifact landed.\n\n");

        md.append("## Per class (A served view)\n\n| class | recall@5 | recall@10 | recall@20 | mrr | ndcg@10 | prec@10 | fp@10 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<?, ?> e : servedPerClass.entrySet()) {
            Map<?, ?> v = (Map<?, ?>) e.getValue();
            md.append("| ").append(e.getKey())
                    .append(" | ").append(v.get("recall@5"))
                    .append(" | ").append(v.get("recall@10"))
                    .append(" | ").append(v.get("recall@20"))
                    .append(" | ").append(v.get("mrr"))
                    .append(" | ").append(v.get("ndcg@10"))
                    .append(" | ").append(v.get("evidence_precision@10"))
                    .append(" | ").append(v.get("false_positive_rate@10"))
                    .append(" |\n");
        }
        md.append("\n## Per class (A compliant view — comparable scope with arm B)\n\n")
                .append("| class | recall@5 | recall@10 | recall@20 | mrr | ndcg@10 | prec@10 | fp@10 |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<?, ?> e : compliantPerClass.entrySet()) {
            Map<?, ?> v = (Map<?, ?>) e.getValue();
            md.append("| ").append(e.getKey())
                    .append(" | ").append(v.get("recall@5"))
                    .append(" | ").append(v.get("recall@10"))
                    .append(" | ").append(v.get("recall@20"))
                    .append(" | ").append(v.get("mrr"))
                    .append(" | ").append(v.get("ndcg@10"))
                    .append(" | ").append(v.get("evidence_precision@10"))
                    .append(" | ").append(v.get("false_positive_rate@10"))
                    .append(" |\n");
        }

        md.append("\n## Boundary + resolution axes\n\n")
                .append("- VALIDATION_BOUNDARY_VIOLATIONS (served view): ").append(violations)
                .append(". **Named finding, not a silent patch:** the production vector surface ")
                .append("(ChunkVectorRepository.search) enforces the T-C07 curriculum-scope predicate but ")
                .append("predates T-C05 — the VALIDATED-paper predicate exists only on the lexical ")
                .append("serving-eligible surface (ChunkLexicalRepository.searchServingEligible). The served ")
                .append("view is the production truth; a compliant vector surface is registered as follow-up. ")
                .append("This run changes nothing in serving.\n")
                .append("- SpecificationPoint resolution: NOT SCOREABLE for arm A — zero HUMAN_VALIDATED ")
                .append("chunk→spec mapping rows in the snapshot (concept_attachments = 0; T-C06/F-168 ")
                .append("substrate pending). Recorded as a named data gap, never fabricated.\n")
                .append("- Zero-result queries: ").append(zeroResultQueries)
                .append("/120 (all top-20 hits below the production cosine floor 0.15 — honest empties, ")
                .append("scored as real zeros).\n")
                .append("- Compliant-starved queries: ").append(compliantStarved)
                .append(" (served non-empty but every hit sits on a non-VALIDATED paper — the boundary gap's ")
                .append("user-visible shape).\n\n");

        md.append("## Reading\n\n")
                .append("- Arm A drives the PRODUCTION vector serving path (ContentRetrievalService → ")
                .append("ChunkVectorRepository.search pgvector cosine; ContentVectorRetriever floor 0.15, ")
                .append("kind-agnostic) — no retrieval SQL or scorer was reimplemented or changed. The only ")
                .append("stubbed surface is the embedding CALL itself, replayed from the frozen artifact ")
                .append("through the production EmbeddingProvider port (compute-once-freeze-forever).\n")
                .append("- Served vs compliant view: the served view scores over the T-C07-scoped corpus ")
                .append("(production truth, boundary finding included); the compliant view is a post-hoc ")
                .append("VALIDATED-only filter of the same served top-20 — comparable in SCOPE with arm B, ")
                .append("but it is NOT a serving simulation (a compliant vector surface would re-rank within ")
                .append("the compliant corpus). Treat cross-arm comparisons as evaluation context for the §8 ")
                .append("gate arithmetic, not as promotion evidence.\n")
                .append("- A vs B: arm B's numbers come from run-003-b (production lexical arm, ")
                .append("VALIDATED-served scope, same frozen gold, same formulas). Any hybrid (arm C) verdict ")
                .append("requires the orchestrator, which does not exist yet (the retrieval fabric has port + ")
                .append("4 adapters and ZERO consumers — the registered gap).\n")
                .append("- Determinism: query vectors are frozen artifact rows keyed by the stripped query ")
                .append("text (fail-closed on any unseen text via a deliberately non-IllegalStateException — ")
                .append("the production retriever degrades IllegalStateException to an honest empty, which ")
                .append("would silently corrupt the measurement); chunk vectors are float4-exact artifact ")
                .append("rows re-stored through the real storeEmbedding. No clocks, no randomness; aggregates ")
                .append("byte-identical across the double pass.\n");
        return md.toString();
    }

    private static String fmt(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(e.getKey()).append(' ').append(e.getValue());
        }
        return sb.toString();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required env " + key);
        }
        return value;
    }

    private static void log(String msg) {
        System.out.println("[run-004-a] " + Instant.now() + " " + msg);
    }
}
