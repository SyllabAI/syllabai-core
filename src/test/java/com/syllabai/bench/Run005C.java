package com.syllabai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.syllabai.content.ChunkLexicalRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.Bm25Retriever;
import com.syllabai.retrieval.BoundaryPolicy;
import com.syllabai.retrieval.PgVectorRetrievalProvider;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.ContentVectorRetriever;
import com.syllabai.tutor.ReciprocalRankFusion;
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
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * T-C13 (spec §7 M2): Run 005 — arm C, the hybrid lexical+semantic arm,
 * recorded. The orchestrator under measurement is the PRODUCTION retrieval
 * fabric ({@code com.syllabai.retrieval.RetrievalFabric}, the registered
 * "port + 4 adapters, ZERO consumers" gap, now closed): explicit composition
 * of the two recorded arms — {@code PgVectorRetrievalProvider} (arm A path:
 * ContentVectorRetriever → ContentRetrievalService →
 * ChunkVectorRepository.searchServingEligible (T-C20), pgvector cosine over V11
 * vector(768), T-C07 scope, cosine floor 0.15,
 * kind-agnostic) and {@code Bm25Retriever} (arm B path: T-C14 Postgres FTS
 * ts_rank_cd over V28 content_tsv, T-C07 scope + T-C05 VALIDATED serving) —
 * fused by the shipped {@code ReciprocalRankFusion} (k=60), rank-only,
 * score-free, NoReranker. Zero API calls at run time — the frozen
 * embed-backfill-snap-001 artifact carries both chunk and gold query vectors
 * (compute-once-freeze-forever, sessions 92/94/96).
 *
 * <p>Dual view (honesty rules, §10 ruling 1) — <em>T-C20 UPDATE: the production
 * vector surface is now itself VALIDATED-only (searchServingEligible), so on any
 * re-record the SERVED view is expected to carry ZERO violations and to agree
 * with the COMPLIANT view; the recorded run-005-c predates that gate and is
 * preserved unchanged:</em></p>
 * <ul>
 *   <li><strong>SERVED view (production truth, ALL denominator):</strong> the
 *   fabric under {@code BoundaryPolicy.allowAll()} over the components exactly
 *   as they stand — the vector surface predates T-C05 (the T-C20 registered
 *   gap), so served hits on non-VALIDATED papers are expected and recorded as
 *   VALIDATION_BOUNDARY_VIOLATION findings. The §8 gate arithmetic is
 *   evaluated HERE (ruling 1: gate on the ALL denominator).</li>
 *   <li><strong>COMPLIANT view (the T-C05-closed configuration):</strong> the
 *   same fabric with the central VALIDATED-only policy applied pre-fusion —
 *   each arm ranks within its servable candidates (the semantics a compliant
 *   production surface would serve). Zero violations BY CONSTRUCTION; the
 *   boundary audit proves it and the run hard-fails on any leak.</li>
 * </ul>
 *
 * <p>Usage (offline, no keys, no API spend; needs one empty Postgres):</p>
 * <pre>
 *   BENCH_JDBC_URL=jdbc:postgresql://localhost:5433/postgres \
 *   BENCH_JDBC_USER=bench BENCH_JDBC_PASSWORD=bench \
 *   BENCH_SNAPSHOT=&lt;snapshot dir&gt; BENCH_GOLD=&lt;gold dir&gt; \
 *   BENCH_EMBED_ARTIFACT=&lt;embed-backfill-snap-001 dir&gt; \
 *   BENCH_RUN_OUT=&lt;output dir&gt; BENCH_CORE_COMMIT=&lt;sha&gt; \
 *   [BENCH_RUN003B_RESULTS=...] [BENCH_RUN004A_RESULTS=...] [BENCH_RUN002A0_RESULTS=...] \
 *   java -cp target/test-classes:target/classes:&lt;deps&gt; com.syllabai.bench.Run005C
 * </pre>
 *
 * <p>Determinism contract (spec §6): no clocks in scoring (BENCH_RUN_DATE pins
 * the date; latency is an ops-axis field, measured outside scoring and never
 * mixed into rankings), the artifact is verified against the exact frozen
 * inputs before anything runs, scoring is recomputed twice in-process (both
 * views) and the serialized aggregates must be byte-identical or the run
 * aborts.</p>
 */
public final class Run005C {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ratified §8 v1.0 lines (§10 ruling 1: B-proxy ALL + 10% / +0.05 / +0.05). */
    private static final double GATE_RECALL10 = 0.3249;
    private static final double GATE_MRR = 0.2964;
    private static final double GATE_NDCG10 = 0.4799;

    private Run005C() {
    }

    public static void main(String[] args) throws Exception {
        Path snapshotDir = Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot"));
        Path goldDir = Path.of(env("BENCH_GOLD", "bench/inputs/gold"));
        Path artifactDir = Path.of(required("BENCH_EMBED_ARTIFACT"));
        Path runOut = Path.of(env("BENCH_RUN_OUT", "evidence/bench-001/runs/run-005-c"));
        Path run003b = Path.of(env("BENCH_RUN003B_RESULTS",
                "evidence/bench-001/runs/run-003-b/results.json"));
        Path run004a = Path.of(env("BENCH_RUN004A_RESULTS",
                "evidence/bench-001/runs/run-004-a/results.json"));
        Path run002a0 = Path.of(env("BENCH_RUN002A0_RESULTS",
                "evidence/bench-001/runs/run-002-a0/results.json"));
        String coreCommit = env("BENCH_CORE_COMMIT", "unrecorded");
        String runDate = env("BENCH_RUN_DATE", "2026-09-17");
        int perArmLimit = Integer.parseInt(env("BENCH_PER_ARM_LIMIT", "20"));

        String url = required("BENCH_JDBC_URL");
        String user = required("BENCH_JDBC_USER");
        String pass = required("BENCH_JDBC_PASSWORD");

        BenchSnapshot snapshot = BenchSnapshot.load(snapshotDir);
        BenchGold gold = BenchGold.load(goldDir);

        Flyway.configure().dataSource(url, user, pass).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Result result = run(jdbc, snapshotDir, snapshot, gold, goldDir, artifactDir, runOut,
                coreCommit, runDate, run003b, run004a, run002a0, perArmLimit);
        System.out.println("run-005-c recorded");
        System.out.println("C served overall: " + result.servedOverall());
        System.out.println("C compliant overall: " + result.compliantOverall());
        System.out.println("boundary findings (served): " + result.violations()
                + " | zero-result queries: " + result.zeroResultQueries()
                + " | compliant-starved: " + result.compliantStarved());
        System.out.println("S8 verdict (served view, ALL denominator): " + result.gateVerdict());
    }

    record Result(String status, int corpusChunks, int queries,
                  Map<String, Object> servedOverall, Map<String, Object> compliantOverall,
                  int violations, int zeroResultQueries, int compliantStarved,
                  String gateVerdict) {
    }

    /** Injectable core — mirrors the Run004A shape (CI-drivable over a seeded fixture). */
    static Result run(JdbcTemplate jdbc, Path snapshotDir, BenchSnapshot snapshot, BenchGold gold,
                      Path goldDir, Path artifactDir, Path runOut, String coreCommit,
                      String runDate, Path run003bResults, Path run004aResults,
                      Path run002a0Results, int perArmLimit) throws Exception {

        // ── 0. verify the frozen artifact against the exact frozen inputs ────
        log("verifying artifact checksums (fail-closed)");
        Run004A.verifyArtifact(artifactDir, snapshotDir, goldDir, gold, snapshot, true);

        // ── 1. real Postgres, real migrations, frozen corpus ─────────────────
        Run003B.SnapshotLoad load = Run003B.loadSnapshot(jdbc, snapshot);
        log("loading snap-001 corpus through the production loader");
        int dbChunks = jdbc.queryForObject("select count(*) from document_chunks", Integer.class);
        if (dbChunks != snapshot.chunkCount()) {
            throw new IllegalStateException("document_chunks " + dbChunks
                    + " != snapshot chunks " + snapshot.chunkCount() + " (fail-closed)");
        }
        CurriculumScope scope = load.scope();
        Map<String, String> paperState = Run004A.paperStateByDocumentId(snapshot);
        long validatedCorpus = paperState.values().stream()
                .filter("VALIDATED"::equals).count();

        // ── 2. apply the artifact's chunk vectors (bit-exact replay) ─────────
        log("applying frozen chunk vectors");
        int applied = Run004A.applyChunkVectors(jdbc, artifactDir, snapshot);
        Run004A.assertStoredState(jdbc, artifactDir);

        // ── 3. the production fabric: explicit arms + shipped fusion ─────────
        EmbeddingProvider frozen = Run004A.frozenQueryProvider(artifactDir, gold);
        ContentVectorRetriever vectorRetriever = ArmA.productionRetriever(
                new ArmA.JdbcTemplateHolder(jdbc), frozen);
        RetrievalProvider semantic = new PgVectorRetrievalProvider(vectorRetriever);
        RetrievalProvider lexical = new Bm25Retriever(
                new ChunkLexicalRepository(jdbc), ArmA.stubDocumentRepository());
        ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

        RetrievalFabric servedFabric = new RetrievalFabric(
                List.of(semantic, lexical), fusion, BoundaryPolicy.allowAll());
        RetrievalFabric compliantFabric = new RetrievalFabric(
                List.of(semantic, lexical), fusion,
                candidate -> "VALIDATED".equals(paperState.get(candidate.documentId())));

        // ── 4. per-query scoring: served view (ALL) + compliant view (gate-on)
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
        List<Long> servedNanos = new ArrayList<>();
        List<Long> compliantNanos = new ArrayList<>();

        for (BenchGold.GoldRecord rec : gold.records()) {
            Map<String, Integer> tierByRef = new LinkedHashMap<>();
            rec.goldEvidence().forEach(e -> tierByRef.put(e.chunkRef(), e.tier()));

            long t0 = System.nanoTime();
            List<RetrievalFabric.FusedCandidate> served = servedFabric.retrieve(
                    StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit));
            servedNanos.add(System.nanoTime() - t0);
            List<String> servedRefs = refs(served);
            List<Double> servedScores = scores(served);
            List<String> servedViolations = ArmB.audit(servedRefs, paperState);
            violations += servedViolations.size();
            violationRefs.addAll(servedViolations);
            if (servedRefs.isEmpty()) {
                zeroResultQueries++;
            }

            long t1 = System.nanoTime();
            List<RetrievalFabric.FusedCandidate> compliant = compliantFabric.retrieve(
                    StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit));
            compliantNanos.add(System.nanoTime() - t1);
            List<String> compliantRefs = refs(compliant);
            List<String> compliantLeaks = ArmB.audit(compliantRefs, paperState);
            if (!compliantLeaks.isEmpty()) {
                throw new IllegalStateException("central T-C05 gate LEAKED on query " + rec.id()
                        + ": " + compliantLeaks + " (fail-closed — the compliant view must be "
                        + "zero-violation by construction)");
            }
            if (!servedRefs.isEmpty() && compliantRefs.isEmpty()) {
                compliantStarved++;
            }

            if (rec.goldEvidence().isEmpty()) {
                noLabelIds.add(rec.id());
                continue;
            }
            labeled.add(rec);
            servedRows.add(BenchMetrics.scoreChunks(servedRefs, tierByRef));
            compliantRows.add(BenchMetrics.scoreChunks(compliantRefs, tierByRef));
            perQueryServed.put(rec.id(), perQuery(servedRefs, servedScores,
                    servedNanos.get(servedNanos.size() - 1)));
            perQueryCompliant.put(rec.id(), perQuery(compliantRefs, scores(compliant),
                    compliantNanos.get(compliantNanos.size() - 1)));
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

        JsonNode manifest = JSON.readTree(Files.readString(artifactDir.resolve("manifest.json"),
                StandardCharsets.UTF_8));
        Map<String, Object> counts = new LinkedHashMap<>();
        manifest.path("counts").fields()
                .forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt()));

        // ── 6. §8 gate arithmetic (ruling 1: evaluated on the ALL denominator
        //      = the served view) + prior-arm context ─────────────────────────
        Map<String, Object> gate = gateArithmetic(servedOverall, violations);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("A0_run_002", overallOf(run002a0Results, "chunk_axis", "all_chunks"));
        context.put("B_run_003", overallOf(run003bResults, "chunk_axis", "validated_only_served"));
        context.put("A_run_004_served", overallOf(run004aResults, "chunk_axis", "served_view"));
        context.put("A_run_004_compliant", overallOf(run004aResults, "chunk_axis", "compliant_view"));
        context.put("note", "arm C = fabric over the recorded arms A+B; same frozen gold, "
                + "same formulas; A0 chunk axis is the zero baseline");

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("run_id", "run-005-c");
        results.put("date", runDate);
        results.put("arm", "C hybrid — PRODUCTION retrieval fabric (com.syllabai.retrieval."
                + "RetrievalFabric: explicit arms [pgvector, bm25], central BoundaryPolicy, "
                + "shipped ReciprocalRankFusion k=60, rank-only, NoReranker) over arm A's "
                + "production vector path and arm B's production lexical path; chunk+query "
                + "vectors replayed from the frozen artifact embed-backfill-snap-001, zero "
                + "API calls at run time");
        results.put("arm_status", "RUNNABLE — this run (the fabric orchestrator landed with the "
                + "run; arm promotion must stay explicit, never injection-implied); benchmark "
                + "arm only, NOT a production serving default — nothing in production "
                + "constructs a RetrievalFabric yet");
        results.put("code_version", coreCommit);
        results.put("gold_set", "gold-v1 (120 queries; frozen)");
        results.put("snapshot", "snap-001 (" + snapshot.snapshotVersion() + ")");
        results.put("executor", "production code over real Postgres migrated V1..V28 (Flyway), "
                + "corpus loaded from the frozen snapshot; chunk vectors applied from the "
                + "checksummed compute-once-freeze-forever artifact and query vectors served "
                + "through the production EmbeddingProvider port; no retrieval SQL changed, "
                + "no fusion code written (the shipped ReciprocalRankFusion runs unchanged)");
        results.put("fabric", Map.of(
                "providers", List.of(semantic.id(), lexical.id()),
                "fusion", "ReciprocalRankFusion k=60 (the shipped serving fuser)",
                "per_arm_limit", perArmLimit,
                "served_boundary", "BoundaryPolicy.allowAll() — production-truth components as "
                        + "they stand (vector surface predates T-C05: the T-C20 registered gap)",
                "compliant_boundary", "central VALIDATED-only policy applied PRE-fusion (the "
                        + "T-C05 closure shape; each arm ranks within its servable candidates)"));
        results.put("embedding_artifact", Map.of(
                "run_id", manifest.path("run_id").asText(),
                "model", manifest.path("model").asText(),
                "dimension", manifest.path("dimension").asInt(),
                "counts", counts,
                "backfill_core_commit", manifest.path("core_commit").asText(),
                "backfill_run_date", manifest.path("run_date").asText(),
                "sha256_echo", EmbedBackfill.Manifests.filesSha256(artifactDir)));
        results.put("evaluation_contract", Map.of(
                "chunk_axis", "Recall@5/10/20, MRR (first tier-2 hit in top-20), nDCG@10 "
                        + "(2/1/0 tiers), evidence precision@10 and FP@10 over the fixed "
                        + "top-10 denominator — formulas identical to run-001/002/003/004 "
                        + "(BenchMetrics, pinned).",
                "served_view", "the fabric under allowAll over the components as they stand — "
                        + "production truth, ALL denominator (ruling 1's gate target); boundary "
                        + "violations expected from the vector leg and recorded, never patched",
                "compliant_view", "the same fabric with the central VALIDATED-only policy "
                        + "applied pre-fusion — zero violations by construction (audited "
                        + "per query; any leak hard-fails the run); comparable in SCOPE with "
                        + "arm B and with arm A's post-hoc compliant view",
                "latency_axis", "per-query retrieval nanoseconds (p50/p95), measured outside "
                        + "scoring; the frozen replay excludes the production query-embedding "
                        + "call, so a deployed hybrid adds one embedding round-trip to these "
                        + "numbers (recorded as the §8(e) caveat)",
                "spec_resolution_axis", "NOT SCOREABLE for arm C: zero HUMAN_VALIDATED "
                        + "chunk→spec mapping rows in the snapshot (concept_attachments = 0, "
                        + "the T-C06/F-168 mapping substrate is pending) — a resolution number "
                        + "would be fabrication; recorded as a named data gap"));
        results.put("queries_total", gold.records().size());
        results.put("queries_scored_chunks", labeled.size());
        results.put("queries_excluded_no_chunk_labels", Map.of(
                "count", noLabelIds.size(),
                "ids", noLabelIds,
                "reason", "no chunk labels (substrate-absent classes / sparse auto-labels) — "
                        + "excluded from the chunk axis only; same rule as run-001..004"));
        results.put("queries_with_zero_results", zeroResultQueries);
        results.put("queries_compliant_starved", compliantStarved);
        results.put("chunk_axis", Map.of(
                "served_view", Map.of(
                        "scope", "fabric(allowAll) over the T-C07-scoped embedded corpus "
                                + "(production truth, ALL denominator)",
                        "corpus_n", dbChunks,
                        "queries_scored", labeled.size(),
                        "overall", servedOverall,
                        "per_class", servedPerClass),
                "compliant_view", Map.of(
                        "scope", "fabric(central VALIDATED gate, pre-fusion) — the T-C05-"
                                + "closed configuration",
                        "corpus_n", validatedCorpus,
                        "queries_scored", labeled.size(),
                        "overall", compliantOverall,
                        "per_class", compliantPerClass),
                "validation_boundary_violations", Map.of(
                        "served", violations,
                        "compliant", 0),
                "violation_refs_served", violationRefs));
        results.put("latency", Map.of(
                "served", percentiles(servedNanos),
                "compliant", percentiles(compliantNanos),
                "note", "retrieval-only (frozen query vectors); a deployed hybrid adds the "
                        + "production query-embedding round-trip"));
        results.put("s8_gate", gate);
        results.put("context", context);
        results.put("arms_registry", Map.of(
                "A0", "RUNNABLE — recorded in run-002-a0 (production baseline)",
                "B", "RUNNABLE — recorded in run-003-b (production lexical arm)",
                "B-proxy", "RECORDED (run-001) — harness-internal probe; never citable",
                "A", "RUNNABLE — recorded in run-004-a (production semantic arm)",
                "C", "RUNNABLE — this run (hybrid over the production fabric)",
                "D", "UNAVAILABLE — C + reranker behind the EvidenceReranker port",
                "E/F/G", "UNAVAILABLE — require T-C15",
                "H1/H2/H3/I", "UNAVAILABLE — prerequisites unchanged"));
        results.put("per_query_chunks", perQueryServed);
        results.put("per_query_compliant", perQueryCompliant);
        if (!context.isEmpty()) {
            results.put("context_prior_arms", context);
        }

        // ── 7. determinism: recomputed scoring + byte-stable serialization ───
        List<BenchMetrics.ChunkRow> secondServed = new ArrayList<>();
        List<BenchMetrics.ChunkRow> secondCompliant = new ArrayList<>();
        for (BenchGold.GoldRecord rec : gold.records()) {
            if (rec.goldEvidence().isEmpty()) {
                continue;
            }
            List<String> againServed = refs(servedFabric.retrieve(
                    StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit)));
            secondServed.add(BenchMetrics.scoreChunks(againServed, tiers(rec)));
            List<String> againCompliant = refs(compliantFabric.retrieve(
                    StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit)));
            secondCompliant.add(BenchMetrics.scoreChunks(againCompliant, tiers(rec)));
        }
        ObjectMapper stable = new ObjectMapper();
        stable.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        checkEquals(stable.writeValueAsString(BenchMetrics.aggregateChunks(secondServed)),
                stable.writeValueAsString(servedOverall), "served view");
        checkEquals(stable.writeValueAsString(BenchMetrics.aggregateChunks(secondCompliant)),
                stable.writeValueAsString(compliantOverall), "compliant view");
        results.put("determinism_check", "PASS — scoring recomputed twice in-process (second "
                + "full retrieval pass), both views' aggregates byte-identical; serialization "
                + "byte-stable");

        // ── 8. write evidence (results.json + RUN_REPORT.md + SHA256SUMS) ────
        Files.createDirectories(runOut);
        String pretty = stable.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        Files.writeString(runOut.resolve("results.json"), pretty, StandardCharsets.UTF_8);
        Files.writeString(runOut.resolve("RUN_REPORT.md"),
                report(runDate, results, servedOverall, compliantOverall, servedPerClass,
                        compliantPerClass, labeled.size(), noLabelIds.size(), zeroResultQueries,
                        compliantStarved, violations, dbChunks, validatedCorpus, context,
                        manifest, gate, perArmLimit),
                StandardCharsets.UTF_8);
        StringBuilder sums = new StringBuilder();
        for (String name : List.of("results.json", "RUN_REPORT.md")) {
            byte[] bytes = Files.readAllBytes(runOut.resolve(name));
            sums.append(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
            sums.append("  ").append(name).append('\n');
        }
        Files.writeString(runOut.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);

        return new Result("RECORDED", dbChunks, gold.records().size(),
                servedOverall, compliantOverall, violations, zeroResultQueries,
                compliantStarved, (String) gate.get("verdict"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Integer> tiers(BenchGold.GoldRecord rec) {
        Map<String, Integer> tiers = new LinkedHashMap<>();
        rec.goldEvidence().forEach(e -> tiers.put(e.chunkRef(), e.tier()));
        return tiers;
    }

    /** Portable evidence identity (spec §3.3): document checksum + chunk ordinal. */
    private static List<String> refs(List<RetrievalFabric.FusedCandidate> fused) {
        List<String> refs = new ArrayList<>(fused.size());
        for (RetrievalFabric.FusedCandidate f : fused) {
            RetrievalCandidate c = f.candidate();
            String ordinal = c.metadata().getOrDefault("chunk_index", "-1");
            refs.add(c.documentId() + ":" + ordinal);
        }
        return refs;
    }

    private static List<Double> scores(List<RetrievalFabric.FusedCandidate> fused) {
        List<Double> scores = new ArrayList<>(fused.size());
        for (RetrievalFabric.FusedCandidate f : fused) {
            scores.add(f.fusedScore());
        }
        return scores;
    }

    private static Map<String, Object> perQuery(List<String> refs, List<Double> scores,
                                                long nanos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ranked_refs", refs);
        m.put("fused_scores", scores);
        m.put("retrieval_ms", nanos / 1_000_000.0);
        return m;
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

    /** Ratified §8 v1.0 arithmetic (ruling 1): evaluated on the ALL denominator = served view. */
    private static Map<String, Object> gateArithmetic(Map<String, Object> servedOverall,
                                                      int violations) {
        double recall10 = asDouble(servedOverall.get("recall@10"));
        double mrr = asDouble(servedOverall.get("mrr"));
        double ndcg10 = asDouble(servedOverall.get("ndcg@10"));
        boolean a = recall10 >= GATE_RECALL10;
        boolean b = mrr >= GATE_MRR;
        boolean c = ndcg10 >= GATE_NDCG10;
        boolean f = violations == 0;
        boolean promoted = a && b && c && f;
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("version", "spec §8 v1.0 (ratified 2026-09-17) + §10 ruling 1: gate arithmetic "
                + "evaluated on the ALL denominator (the served view); floors = B-proxy ALL "
                + "+10% relative / +0.05 absolute (0.2954→0.3249, 0.2464→0.2964, 0.4299→0.4799)");
        gate.put("evaluated_on", "served view (ALL denominator)");
        gate.put("a_recall@10", Map.of("value", recall10, "floor", GATE_RECALL10, "pass", a));
        gate.put("b_mrr", Map.of("value", mrr, "floor", GATE_MRR, "pass", b));
        gate.put("c_ndcg@10", Map.of("value", ndcg10, "floor", GATE_NDCG10, "pass", c));
        gate.put("d_spec_resolution", "NOT SCOREABLE — zero HUMAN_VALIDATED chunk→SP rows "
                + "(named data gap; nothing to regress, nothing to claim)");
        gate.put("e_p95_latency", "NOT EVALUABLE FROM RECORDS — A0 p95 was not recorded; this "
                + "run records retrieval-only p50/p95 (frozen replay excludes the production "
                + "query-embedding call, which a deployed hybrid adds)");
        gate.put("f_boundary", Map.of("served_violations", violations, "pass", f,
                "note", f ? "zero violations" : "non-VALIDATED hits surfaced — the T-C20 "
                        + "vector-surface gap; hard fail condition per spec §5.1"));
        gate.put("g_per_class", "PASS trivially — A0's chunk axis is all zeros, no class can "
                + "regress against the zero baseline; per-class detail reported for the record");
        gate.put("verdict", promoted ? "PROMOTED (all ratified checks pass)" : "NOT PROMOTED");
        return gate;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalStateException("metric missing from aggregate: " + v + " (fail-closed)");
    }

    /** Overall chunk-axis map from a prior recorded run (fail-soft: empty when absent). */
    private static Object overallOf(Path results, String axis, String view) {
        try {
            JsonNode node = JSON.readTree(Files.readString(results, StandardCharsets.UTF_8))
                    .path(axis).path(view).path("overall");
            return JSON.convertValue(node, Object.class);
        } catch (Exception e) {
            return "UNAVAILABLE (" + results.getFileName() + ": " + e.getMessage() + ")";
        }
    }

    /** Deterministic p50/p95 (sorted, index-frozen — no interpolation randomness). */
    private static Map<String, Object> percentiles(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double p50 = sorted.isEmpty() ? 0.0 : sorted.get((int) Math.floor(0.5 * (n - 1))) / 1_000_000.0;
        double p95 = sorted.isEmpty() ? 0.0 : sorted.get((int) Math.floor(0.95 * (n - 1))) / 1_000_000.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50_ms", p50);
        m.put("p95_ms", p95);
        m.put("n", n);
        return m;
    }

    // ── RUN_REPORT.md ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String report(String runDate, Map<String, Object> results,
                                 Map<String, Object> servedOverall, Map<String, Object> compliantOverall,
                                 Map<String, Object> servedPerClass, Map<String, Object> compliantPerClass,
                                 int labeledN, int excludedN, int zeroResultQueries, int compliantStarved,
                                 int violations, int corpusN, long validatedCorpus,
                                 Map<String, Object> context, JsonNode manifest,
                                 Map<String, Object> gate, int perArmLimit) {
        StringBuilder md = new StringBuilder();
        md.append("# Run 005 — C hybrid arm, first recorded run (the retrieval fabric orchestrator)\n\n");
        md.append("**Status:** RECORDED — production hybrid arm on record (deterministic, offline ")
                .append("replay, zero API calls; snapshot snap-001).\n");
        md.append("**Arm:** ").append(results.get("arm")).append("\n");
        md.append("**Executor:** ").append(results.get("executor")).append(" — code `")
                .append(results.get("code_version")).append("`.\n");
        md.append("**Date:** ").append(runDate).append(" | **Gold:** gold-v1 frozen | ")
                .append("**Determinism:** double retrieval pass, byte-identical aggregates (both views).\n\n");

        md.append("## Fabric provenance\n\n")
                .append("- Orchestrator: the registered gap CLOSED — `RetrievalFabric` composes the ")
                .append("explicit arms [pgvector, bm25] (never injection-implied, E-1 forward note), ")
                .append("applies the serving boundary ONCE centrally pre-fusion, and fuses with the ")
                .append("shipped `ReciprocalRankFusion` k=60 — no new fusion code, no retrieval SQL ")
                .append("changed. Per-arm candidate bound ").append(perArmLimit).append(".\n")
                .append("- Frozen artifact `").append(manifest.path("run_id").asText()).append("`: model `")
                .append(manifest.path("model").asText()).append("` @ ").append(manifest.path("dimension").asInt())
                .append(" dims; verified fail-closed against this run's frozen inputs before anything ran; ")
                .append("SHA-256 echo in results.json `embedding_artifact.sha256_echo`.\n\n");

        md.append("## Overall (chunk axis, n=").append(labeledN).append(" labeled queries)\n\n");
        md.append("- **C served (fabric over components as they stand, ALL denominator, ")
                .append(corpusN).append(" embedded chunks):** ").append(fmt(servedOverall)).append("\n");
        md.append("- **C compliant (central VALIDATED gate pre-fusion, ").append(validatedCorpus)
                .append(" reachable chunks — the T-C05-closed configuration):** ")
                .append(fmt(compliantOverall)).append("\n");
        Object a0 = context.get("A0_run_002");
        if (a0 instanceof Map) {
            md.append("- **A0 (run-002, zero-vector baseline):** ").append(fmt((Map<String, Object>) a0)).append("\n");
        }
        Object b = context.get("B_run_003");
        if (b instanceof Map) {
            md.append("- **B (run-003, production lexical, VALIDATED-served):** ").append(fmt((Map<String, Object>) b)).append("\n");
        }
        Object a = context.get("A_run_004_served");
        if (a instanceof Map) {
            md.append("- **A served (run-004, production vector, ALL denominator):** ").append(fmt((Map<String, Object>) a)).append("\n");
        }
        Object ac = context.get("A_run_004_compliant");
        if (ac instanceof Map) {
            md.append("- **A compliant view (run-004, post-hoc VALIDATED-only filter):** ").append(fmt((Map<String, Object>) ac)).append("\n");
        }
        md.append("\n");

        md.append("## Per class (C served view)\n\n| class | recall@5 | recall@10 | recall@20 | mrr | ndcg@10 | prec@10 | fp@10 |\n");
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
        md.append("\n## Per class (C compliant view — T-C05-closed configuration)\n\n")
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

        md.append("\n## S8 gate arithmetic (ratified v1.0; ruling 1: ALL denominator = served view)\n\n");
        md.append("- (a) Recall@10: **").append(fmtGate(gate, "a_recall@10")).append("\n");
        md.append("- (b) MRR: **").append(fmtGate(gate, "b_mrr")).append("\n");
        md.append("- (c) nDCG@10: **").append(fmtGate(gate, "c_ndcg@10")).append("\n");
        md.append("- (d) SpecificationPoint resolution: not scoreable (zero chunk-to-SP ")
                .append("HUMAN_VALIDATED rows — named data gap; nothing to regress).\n");
        md.append("- (e) p95 latency: not evaluable from records (A0 p95 not recorded); this run ")
                .append("records retrieval-only p50/p95; a deployed hybrid adds the production ")
                .append("query-embedding round-trip.\n");
        md.append("- (f) Validation boundary: served view surfaced **").append(violations)
                .append("** non-VALIDATED hits (the T-C20 vector-surface gap) — hard fail per §5.1 ")
                .append("for the as-served configuration; the compliant view audited **0** by ")
                .append("construction.\n");
        md.append("- (g) Per-class regression: trivially satisfied vs the zero A0 baseline.\n");
        md.append("- **VERDICT: ").append(gate.get("verdict")).append("**\n\n");

        md.append("## Boundary + resolution axes\n\n")
                .append("- VALIDATION_BOUNDARY_VIOLATIONS (served view): ").append(violations)
                .append(". **Named finding, not a silent patch:** the production vector surface ")
                .append("predates T-C05; the compliant view's central pre-fusion gate (this run's ")
                .append("new production capability) audited ZERO violations across all ")
                .append(labeledN + excludedN).append(" queries — the gate is the T-C20 closure shape.\n")
                .append("- Zero-result queries (served): ").append(zeroResultQueries)
                .append("/").append(labeledN + excludedN).append(".\n")
                .append("- Compliant-starved queries: ").append(compliantStarved)
                .append(" (served non-empty but every eligible-rank hit sits on a non-VALIDATED ")
                .append("paper).\n")
                .append("- SpecificationPoint resolution: NOT SCOREABLE (zero HUMAN_VALIDATED ")
                .append("chunk-to-SP mapping rows; T-C06/F-168 substrate pending) — recorded as a ")
                .append("named data gap, never fabricated.\n\n");

        md.append("## Reading\n\n")
                .append("- The fabric is production code but NOT a serving default: nothing in ")
                .append("production constructs a RetrievalFabric; promotion happens in the owning ")
                .append("lane with its own verification discipline after the owner accepts a ")
                .append("verdict.\n")
                .append("- C vs A/B: fusion rewards agreement; read the dual view against the ")
                .append("recorded arms. The compliant view is the configuration a promotion would ")
                .append("actually serve; the served view is the production-truth measurement the ")
                .append("ratified gate runs on.\n")
                .append("- Determinism: no clocks in scoring (latency is an ops field, measured ")
                .append("outside rankings); aggregates byte-identical across the double pass.\n");
        return md.toString();
    }

    private static String fmtGate(Map<String, Object> gate, String key) {
        Object raw = gate.get(key);
        if (raw instanceof Map<?, ?> m) {
            boolean pass = Boolean.TRUE.equals(m.get("pass"));
            return m.get("value") + "** vs floor " + m.get("floor") + " -> " + (pass ? "PASS" : "FAIL") + "\n";
        }
        return String.valueOf(raw) + "\n";
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
        System.out.println("[run-005-c] " + Instant.now() + " " + msg);
    }
}
