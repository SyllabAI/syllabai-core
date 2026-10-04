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
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
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
 * vector(768), T-C07 scope, cosine floor = {@code ContentVectorRetriever#MIN_COSINE}
 * (T-C42-calibrated 0.50; run-005-c through r7 were recorded pre-calibration
 * at 0.15, r8 at 0.50),
 * kind-agnostic) and {@code Bm25Retriever} (arm B path: T-C14 Postgres FTS
 * ts_rank_cd over V28 content_tsv, T-C07 scope + T-C05 VALIDATED serving) —
 * fused by the shipped {@code ReciprocalRankFusion} (k=60), rank-only,
 * score-free, NoReranker. Zero API calls at run time — the frozen
 * embed-backfill artifact (BENCH_EMBED_ARTIFACT) carries both chunk and gold
 * query vectors (compute-once-freeze-forever, sessions 92/94/96).
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
 *   BENCH_EMBED_ARTIFACT=&lt;embed artifact dir&gt; \
 *   BENCH_RUN_OUT=&lt;output dir&gt; BENCH_CORE_COMMIT=&lt;sha&gt; \
 *   [BENCH_RUN003B_RESULTS=...] [BENCH_RUN004A_RESULTS=...] [BENCH_RUN002A0_RESULTS=...] \
 *   java -cp target/test-classes:target/classes:&lt;deps&gt; com.syllabai.bench.Run005C
 * </pre>
 *
 * <p>Arm D mode (T-C63 rank-quality lane): {@code BENCH_RUN_ID} (default
 * "run-005-c" — the r9-era hardcode env-driven per the T-C40 bproxy
 * env-identity lesson) and {@code BENCH_ARM_D_RERANKER}
 * ({@code lexical_precision}, default absent = NoReranker). With a reranker
 * spec set, BOTH fabrics wrap in {@link RerankedRetrieval#maybeWrap}: the
 * pre-registered deterministic reranker reorders the fused candidates
 * DOWNSTREAM of fusion behind the production {@code EvidenceReranker} port
 * (registry row D) — the pre-fusion boundary is untouched, so (f) is
 * structurally unaffected. The default (absent) path stays byte-identical by
 * construction: the fabrics are the bare fabric method references (the S8D
 * absent-path precedent). §8(d) scores the RERANKED served lists —
 * compositionality recovery is exactly what an arm-D run measures.</p>
 *
 * <p>Pool-composition mode (T-C69, charter tranche 5): {@code BENCH_NOTES_FLOOR}
 * (positive integer, default absent) arms BOTH providers with the per-arm
 * EXTERNAL_NOTES floor — {@link NotesFloorRetrieval} appends up to N
 * EXTERNAL_NOTES candidates beyond each arm's recorded cut, in arm-rank order,
 * PRE-boundary (the boundary and the fusion are untouched; the compliant view
 * keeps excluding non-VALIDATED papers exactly as recorded). Floor form over
 * cap form is the pre-registration's declared design choice: pure-additive
 * admission keeps the T-C67 superset invariant and its §8(d) monotonicity
 * theorem structurally alive. The default (absent) path stays byte-identical
 * by construction: the providers stay bare (never wrapped). The one-lever
 * guard is now three-way: reranker × weights × floor are mutually exclusive,
 * fail-closed.</p>
 *
 * <p>Arm-composition mode (T-C72, charter tranche 6): {@code
 * BENCH_NOTES_KIND_ARM} (positive integer, default absent) adds a THIRD
 * fusion input — the per-kind EXTERNAL_NOTES arm (kind-arm) — {@link
 * NotesArmRetrieval}: arm A's production candidate SQL + the kind predicate +
 * {@code LIMIT K} + a deterministic {@code chunk_ref ASC} final tiebreak, the
 * same frozen query vector through the production {@code EmbeddingProvider}
 * port, entering BOTH fabrics as an ordinary third arm input (the shipped
 * unweighted 3-arg fusion, the existing arms' lists byte-unchanged, the
 * central boundary pre-fusion as shipped). KIND-ARM form over MIN_COSINE is
 * the pre-registration's declared design choice (the reach census shows the
 * carriers buried only in the arms' cross-kind ordering; a cosine threshold
 * is mechanically incapable of extending count-cut reach). The default
 * (absent) path stays byte-identical by construction: the arm is never
 * constructed. The one-lever guard is now four-way: reranker × weights ×
 * floor × kind-arm are mutually exclusive, fail-closed.</p>
 *
 * <p>Determinism contract (spec §6): no clocks in scoring (BENCH_RUN_DATE pins
 * the date; latency is an ops-axis field, measured outside scoring and never
 * mixed into rankings), the artifact is verified against the exact frozen
 * inputs before anything runs, scoring is recomputed twice in-process (both
 * views) and the serialized aggregates must be byte-identical or the run
 * aborts.</p>
 *
 * <p>Precision-side mode (T-C83, charter tranche 8): {@code BENCH_MIN_COSINE}
 * (double in (0, 1], default absent) trims the vector arm's POST-cut
 * candidates to a minimum cosine — {@link VectorMinCosineRetrieval}: the
 * PRODUCTION constant's own filter shape ({@code ContentVectorRetriever}
 * applies {@code MIN_COSINE} as a post-cut stream filter; the candidate's own
 * native cosine is the signal), REMOVAL-ONLY (the list under-fills and never
 * backfills — the pre-cut backfill form was rejected, the T-C69 CAP-form
 * ground: a pre-LIMIT predicate conflates removal with admission), the bm25
 * arm untouched (bm25 has no cosine), the central boundary pre-fusion as
 * shipped. Survivors keep their positions, so their fused RRF scores are
 * byte-identical and the shipped fusion's tie-break (score, source, stableKey)
 * is position-independent — the whole posture's delta is the removed-ref
 * ledger, and the run pool is a per-query SUBSET of the recorded pool (the
 * T-C67 theorem's inverted form: §8(d) monotone non-increasing, by
 * construction). The default (absent) path stays byte-identical by
 * construction: the provider stays bare (never wrapped). The one-lever guard
 * is now six-way: reranker × weights × floor × kind-arm (with its rank-bound)
 * × min-cosine are mutually exclusive, fail-closed.</p>
 */
public final class Run005C {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ratified §8 v1.0 lines (§10 ruling 1: B-proxy ALL + 10% / +0.05 / +0.05). RETAINED
     *  for the recorded-era reference column — v1.0 verdicts on recorded runs stand unchanged
     *  (spec §8: new thresholds mean new runs; no recorded verdict is re-judged). */
    private static final double GATE_RECALL10 = 0.3249;
    private static final double GATE_MRR = 0.2964;
    private static final double GATE_NDCG10 = 0.4799;

    /** Re-indexed §8 v1.1 bars (spec §8.1, T-C40 ②, 2026-10-01): DUAL-VIEW, derived from the
     *  run-006-bproxy re-baseline (snap-006 × gold-v5) with the SAME arithmetic as v1.0 applied
     *  per denominator view. The v1.0 bars were indexed to the Run-1 ALL-corpus baseline while a
     *  compliant surface may only touch VALIDATED chunks — structurally unpassable, which is how
     *  honest scoreboards decay into ritual. From 2026-10-01 gate arithmetic runs on BOTH views:
     *  ALL bars on the served view (ruling 1 unchanged) AND VALIDATED bars on the compliant view;
     *  promotion requires both. */
    private static final double GATE_V11_ALL_RECALL10 = 0.1920;
    private static final double GATE_V11_ALL_MRR = 0.1237;
    private static final double GATE_V11_ALL_NDCG10 = 0.2316;
    private static final double GATE_V11_VALIDATED_RECALL10 = 0.0734;
    private static final double GATE_V11_VALIDATED_MRR = 0.1184;
    private static final double GATE_V11_VALIDATED_NDCG10 = 0.1683;

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
        String runId = env("BENCH_RUN_ID", "run-005-c");
        EvidenceReranker armDReranker = RerankedRetrieval.rerankerForSpec(
                env("BENCH_ARM_D_RERANKER", ""));
        Map<EvidenceItem.EvidenceSource, Double> fusionWeights =
                fusionWeightsForSpec(env("BENCH_FUSION_WEIGHTS", ""));
        int notesFloor = NotesFloorRetrieval.floorForSpec(env("BENCH_NOTES_FLOOR", ""));
        int notesKindArm = NotesArmRetrieval.kindArmForSpec(env("BENCH_NOTES_KIND_ARM", ""));
        int notesRankCap = NotesRankCapRetrieval.rankCapForSpec(env("BENCH_NOTES_RANK_CAP", ""));
        double minCosine = VectorMinCosineRetrieval.minCosineForSpec(env("BENCH_MIN_COSINE", ""));
        requireSingleLever(armDReranker, fusionWeights, notesFloor, notesKindArm, notesRankCap,
                minCosine);

        String url = required("BENCH_JDBC_URL");
        String user = required("BENCH_JDBC_USER");
        String pass = required("BENCH_JDBC_PASSWORD");

        BenchSnapshot snapshot = BenchSnapshot.load(snapshotDir);
        BenchGold gold = BenchGold.load(goldDir);

        Flyway.configure().dataSource(url, user, pass).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        Result result = run(jdbc, snapshotDir, snapshot, gold, goldDir, artifactDir, runOut,
                coreCommit, runDate, run003b, run004a, run002a0, perArmLimit, runId,
                notesFloor, notesKindArm, notesRankCap, minCosine, armDReranker, fusionWeights);
        System.out.println(runId + " recorded");
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
                      Path run002a0Results, int perArmLimit, String runId,
                      int notesFloor, int notesKindArm, int notesRankCap, double minCosine,
                      EvidenceReranker armDReranker,
                      Map<EvidenceItem.EvidenceSource, Double> fusionWeights) throws Exception {

        // ── 0. verify the frozen artifact against the exact frozen inputs ────
        log("verifying artifact checksums (fail-closed)");
        Run004A.verifyArtifact(artifactDir, snapshotDir, goldDir, gold, snapshot, true);

        // ── 1. real Postgres, real migrations, frozen corpus ─────────────────
        Run003B.SnapshotLoad load = Run003B.loadSnapshot(jdbc, snapshot);
        log("loading " + snapshot.snapshotVersion() + " corpus through the production loader");
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

        // ── 2b. paired embed_rev stamp (the 2026-09-28 cut-over mirror) ──────
        // The frozen artifact IS the rev2 corpus; the bench loader's inserts
        // land at the V33 default embed_rev=1, and BOTH serving arms gate at
        // ChunkVectorRepository.CURRENT_EMBED_REV — without this paired stamp
        // a post-flip replay reproduces the T-C23 empty funnel in miniature
        // (every chunk invisible, 120/120 zero-result). Production mirror:
        // evidence/serving-rev2-restamp-cutover-2026-09-28 (in-window restamp,
        // serving set provably identical across the boundary).
        int revNow = com.syllabai.content.ChunkVectorRepository.CURRENT_EMBED_REV;
        int restamped = jdbc.update(
                "update document_chunks set embed_rev = ? where embed_rev <> ? and embedding is not null",
                revNow, revNow);
        int atRev = jdbc.queryForObject(
                "select count(*) from document_chunks where embed_rev = ? and embedding is not null",
                Integer.class, revNow);
        if (atRev != dbChunks) {
            throw new IllegalStateException("paired rev stamp incomplete: " + atRev
                    + " chunks at embed_rev=" + revNow + " != " + dbChunks + " (fail-closed)");
        }
        log("paired embed_rev stamp: " + restamped + " chunks -> rev " + revNow
                + " (all " + atRev + " embedded chunks now serving-eligible by rev)");

        // ── 3. the production fabric: explicit arms + shipped fusion ─────────
        EmbeddingProvider frozen = Run004A.frozenQueryProvider(artifactDir, gold);
        ContentVectorRetriever vectorRetriever = ArmA.productionRetriever(
                new ArmA.JdbcTemplateHolder(jdbc), frozen);
        RetrievalProvider semantic = new PgVectorRetrievalProvider(vectorRetriever);
        RetrievalProvider lexical = new Bm25Retriever(
                new ChunkLexicalRepository(jdbc));

        // Precision-side cosine trim (T-C83, charter tranche 8): the vector
        // arm's post-cut candidates filtered to a minimum cosine — the
        // PRODUCTION CONSTANT'S OWN filter shape (ContentVectorRetriever
        // applies MIN_COSINE as a post-cut stream filter; this wrapper is
        // that shape at the bench posture, on the candidate's own native
        // cosine). REMOVAL-ONLY: the list under-fills, never backfills (the
        // pre-cut backfill form was rejected — the T-C69 CAP-form ground: a
        // pre-LIMIT predicate conflates removal with admission). The bm25
        // arm is untouched (bm25 has no cosine). Survivors keep their
        // positions, so their fused RRF scores stay byte-identical and the
        // whole posture's delta is the removed-ref ledger; the run pool is a
        // per-query SUBSET of the recorded pool — the T-C67 theorem's
        // inverted form: §8(d) monotone non-increasing, by construction.
        // Pre-registered in MIN-COSINE-PREREGISTRATION.md BEFORE this run.
        // A theta <= 0 keeps the bare provider: the recorded path is
        // byte-identical by construction (absent-path identity).
        VectorMinCosineRetrieval.MinCosineProvider minCosineProvider = null;
        if (minCosine > 0) {
            minCosineProvider = (VectorMinCosineRetrieval.MinCosineProvider)
                    VectorMinCosineRetrieval.maybeWrap(semantic, minCosine);
            semantic = minCosineProvider;
        }

        // Per-arm EXTERNAL_NOTES floor (T-C69, charter tranche 5): pure-additive
        // admission at the PROVIDER level — AFTER each arm's recorded limit cut,
        // BEFORE the fabric's central boundary and fusion. The compliant view
        // keeps excluding non-VALIDATED papers exactly as recorded (the boundary
        // is untouched); RRF reads the extended lists as ordinary arm output.
        // Pre-registered in POOL-COMPOSITION-PREREGISTRATION.md BEFORE this run
        // (floor form over cap form: the union can only grow, the f→g delta is
        // admission ALONE). A floor <= 0 keeps the bare providers: the recorded
        // path is byte-identical by construction (absent-path identity).
        if (notesFloor > 0) {
            Map<String, String> kindByRef = new LinkedHashMap<>();
            snapshot.chunks().forEach((ref, chunk) -> kindByRef.put(ref, chunk.kind()));
            semantic = NotesFloorRetrieval.maybeWrap(semantic, notesFloor, kindByRef);
            lexical = NotesFloorRetrieval.maybeWrap(lexical, notesFloor, kindByRef);
        }

        // Per-kind EXTERNAL_NOTES arm (T-C72, charter tranche 6): a THIRD
        // fusion input — the kind-scoped delegate over arm A's production
        // candidate SQL (+ the kind predicate, LIMIT K, the deterministic
        // chunk_ref ASC tiebreak), PRE-boundary in BOTH fabrics. The kind-arm
        // replaces the ranking population for the kind: notes compete only
        // against notes (the reach census: the f-lost carriers sit at
        // NOTES-cosine ranks 4–13 of 350 but GLOBAL ranks 56–219 — buried
        // only in the arms' cross-kind ordering). Pre-registered in
        // ARM-COMPOSITION-PREREGISTRATION.md BEFORE this run. K <= 0 adds
        // nothing: the two-provider list is byte-identical by construction
        // (absent-path identity — the arm is never constructed).
        NotesArmRetrieval notesArm = notesKindArm > 0
                ? new NotesArmRetrieval(jdbc, frozen, notesKindArm)
                : null;
        List<RetrievalProvider> arms = composeArms(semantic, lexical, notesArm);

        ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

        // Fusion-weights posture (T-C65, charter tranche 3): the shipped 4-arg
        // ctor runs the shipped PLAN_V2_WEIGHTS per-kind map (plan §7) — the
        // serving posture's map, zero new fusion code. A null map keeps the
        // 3-arg ctor: the recorded unweighted posture, byte-identical by
        // construction (absent-path identity).
        BoundaryPolicy servedBoundary = BoundaryPolicy.allowAll();
        BoundaryPolicy compliantBoundary =
                candidate -> "VALIDATED".equals(paperState.get(candidate.documentId()));
        RetrievalFabric rawServedFabric = fusionWeights == null
                ? new RetrievalFabric(arms, fusion, servedBoundary)
                : new RetrievalFabric(arms, fusion, servedBoundary, fusionWeights);
        RetrievalFabric rawCompliantFabric = fusionWeights == null
                ? new RetrievalFabric(arms, fusion, compliantBoundary)
                : new RetrievalFabric(arms, fusion, compliantBoundary, fusionWeights);
        // Arm D (T-C63): the reranker sits DOWNSTREAM of fusion (registry row D)
        // — the boundary stays pre-fusion inside the raw fabrics, untouched. A
        // null reranker keeps the bare fabric method reference: the recorded
        // arm-C path is byte-identical by construction (absent-path identity).
        RerankedRetrieval.FusedRetriever servedFabric =
                RerankedRetrieval.maybeWrap(rawServedFabric, armDReranker);
        RerankedRetrieval.FusedRetriever compliantFabric =
                RerankedRetrieval.maybeWrap(rawCompliantFabric, armDReranker);

        // Rank-bounded kind-admission (T-C77, charter tranche 7): the
        // post-fusion TWO-ARM HORIZON PARTITION — the fused pool re-partitioned
        // per view: prefix = refs with an arm A/B contribution ordered by the
        // STRIPPED two-arm RRF score (the kind-arm's additive term removed),
        // tail = kind-arm-only refs ordered by kind-arm rank, chunk_ref ASC
        // tiebreaks; the kind-arm admits exactly as recorded (its list, its SET
        // contribution, the pool all unchanged), its exclusive mass serving
        // below the entire two-arm surface. Applied identically to BOTH
        // fabrics, each wrapper carrying its own view boundary so the strip
        // mirrors term-for-term the ranks that view's fusion consumed. Absent
        // gate -> no wrapper: the run-005-h path byte-identical by
        // construction. Pre-registered in
        // RANK-BOUNDED-KIND-ADMISSION-PREREGISTRATION.md BEFORE this run.
        Set<String> twoArmIds = Set.of(semantic.id(), lexical.id());
        NotesRankCapRetrieval servedRankCap = NotesRankCapRetrieval.maybeWrap(
                servedFabric, notesArm, notesRankCap, servedBoundary, twoArmIds);
        if (servedRankCap != null) {
            servedFabric = servedRankCap::retrieve;
        }
        NotesRankCapRetrieval compliantRankCap = NotesRankCapRetrieval.maybeWrap(
                compliantFabric, notesArm, notesRankCap, compliantBoundary, twoArmIds);
        if (compliantRankCap != null) {
            compliantFabric = compliantRankCap::retrieve;
        }

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

        // §8(d) wiring (S8D_SCORING_HANDOFF_2026-09-28 §5): scored on BOTH views
        // when the snapshot carries the chunk→SP HV projection; absent
        // (snap-001..004) the recorded NOT SCOREABLE texts stay byte-identical.
        final boolean hvPresent = snapshot.chunkSpecHvPresent();
        final Map<String, Set<String>> hvCodes = snapshot.hvSpecCodesByChunkRef();
        final List<ChunkSpecHvResolution.QueryResolution> hvRowsServed = new ArrayList<>();
        final List<ChunkSpecHvResolution.QueryResolution> hvRowsCompliant = new ArrayList<>();
        final List<String> hvGoldPoints = new ArrayList<>();

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
            if (hvPresent) {
                hvRowsServed.add(ChunkSpecHvResolution.scoreQuery(rec.id(), servedRefs,
                        rec.goldSpecPoints(), hvCodes));
                hvRowsCompliant.add(ChunkSpecHvResolution.scoreQuery(rec.id(), compliantRefs,
                        rec.goldSpecPoints(), hvCodes));
                hvGoldPoints.addAll(rec.goldSpecPoints());
            }
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
        final Map<String, Object> hvAggServed = hvPresent
                ? ChunkSpecHvResolution.aggregate(hvRowsServed) : Map.of();
        Map<String, Object> gate = gateArithmetic(servedOverall, compliantOverall, violations,
                hvPresent ? hvAggServed : null);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("A0_run_002", overallOf(run002a0Results, "chunk_axis", "all_chunks"));
        context.put("B_run_003", overallOf(run003bResults, "chunk_axis", "validated_only_served"));
        context.put("A_run_004_served", overallOf(run004aResults, "chunk_axis", "served_view"));
        context.put("A_run_004_compliant", overallOf(run004aResults, "chunk_axis", "compliant_view"));
        context.put("note", armLabel(armDReranker, fusionWeights, notesFloor, notesKindArm,
                notesRankCap, minCosine)
                + " = fabric over the recorded arms A+B; same frozen gold, "
                + "same formulas; A0 chunk axis is the zero baseline");

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("run_id", runId);
        results.put("date", runDate);
        results.put("arm", fusionWeights != null
                ? "C hybrid, plan-v2 weighted fusion — the PRODUCTION retrieval fabric "
                        + "(com.syllabai.retrieval.RetrievalFabric: explicit arms [pgvector, bm25], "
                        + "central BoundaryPolicy) with the shipped ReciprocalRankFusion k=60 running "
                        + "the shipped PLAN_V2_WEIGHTS per-kind serving posture (plan §7: NOTE 1.0 / "
                        + "SYLLABUS 0.9 / QUESTION_PAPER 0.8 / TEXTBOOK 0.7 / MARK_SCHEME 0.6 / "
                        + "CARD 0.3; sources absent from the map weigh 1.0; within-arm ranks "
                        + "untouched, only cross-source influence scales) over arm A's production "
                        + "vector path and arm B's production lexical path; chunk+query vectors "
                        + "replayed from the frozen artifact " + manifest.path("run_id").asText()
                        + ", zero API calls at run time"
                : armDReranker == null
                ? "C hybrid — PRODUCTION retrieval fabric (com.syllabai.retrieval."
                        + "RetrievalFabric: explicit arms [pgvector, bm25"
                        + (notesKindArm > 0 ? ", notes-kind-arm" : "")
                        + "], central BoundaryPolicy, "
                        + "shipped ReciprocalRankFusion k=60, rank-only, NoReranker) over arm A's "
                        + "production vector path and arm B's production lexical path"
                        + (notesFloor > 0
                                ? " with the per-arm EXTERNAL_NOTES floor N=" + notesFloor
                                        + " appended beyond each arm's cut, PRE-boundary (the "
                                        + "T-C69 per-kind quota lever, floor form — pure-additive "
                                        + "admission, pre-registered BEFORE this run)"
                                : "")
                        + (notesKindArm > 0
                                ? " with the per-kind EXTERNAL_NOTES arm K=" + notesKindArm
                                        + " as a THIRD fusion input, PRE-boundary (the T-C72 "
                                        + "kind-arm lever, KIND-ARM form — notes compete only "
                                        + "against notes, the existing arms' lists untouched, "
                                        + "pre-registered BEFORE this run)"
                                : "")
                        + (notesRankCap > 0
                                ? " with the kind-arm's exclusive mass rank-bounded below the "
                                        + "two-arm surface H=" + notesRankCap + " (the T-C77 "
                                        + "rank-bound lever, RANK-CAP form — the fused pool "
                                        + "re-partitioned post-fusion: two-arm contributions "
                                        + "STRIPPED of the kind-arm's additive RRF term, "
                                        + "kind-arm-only admissions ordered by kind-arm rank, "
                                        + "chunk_ref ASC tiebreaks, pre-registered BEFORE this run)"
                                : "")
                        + (minCosine > 0
                                ? " with the vector arm's post-cut candidates trimmed to "
                                        + "cosine >= " + minCosine + " (the T-C83 precision-side "
                                        + "MIN_COSINE lever, TRIM form — removal-only, the "
                                        + "production constant's own post-cut filter shape, the "
                                        + "list under-fills and never backfills, pre-registered "
                                        + "BEFORE this run)"
                                : "")
                        + "; chunk+query vectors replayed from the frozen artifact "
                        + manifest.path("run_id").asText()
                        + ", zero API calls at run time"
                : "D hybrid+rerank — the arm-C production fabric (explicit arms [pgvector, bm25], "
                        + "central BoundaryPolicy, shipped ReciprocalRankFusion k=60, rank-only) "
                        + "with the " + armDReranker.getClass().getSimpleName()
                        + " applied DOWNSTREAM of fusion behind the production EvidenceReranker "
                        + "port (registry row D; pre-registered design: the rank-quality-lane "
                        + "charter) over arm A's production vector path and arm B's production "
                        + "lexical path; chunk+query vectors replayed from the frozen artifact "
                        + manifest.path("run_id").asText() + ", zero API calls at run time");
        results.put("arm_status", "RUNNABLE — this run (the fabric orchestrator landed with the "
                + "run; arm promotion must stay explicit, never injection-implied); benchmark "
                + "arm only, NOT a production serving default — nothing in production "
                + "constructs a RetrievalFabric yet");
        results.put("code_version", coreCommit);
        results.put("gold_set", gold.manifest().path("set_version").asText("gold set")
                + " (" + gold.records().size() + " queries; frozen)");
        results.put("snapshot", snapshot.snapshotVersion() + " (frozen snapshot)");
        results.put("executor", "production code over a real Flyway-migrated Postgres, "
                + "corpus loaded from the frozen snapshot; chunk vectors applied from the "
                + "checksummed compute-once-freeze-forever artifact and query vectors served "
                + "through the production EmbeddingProvider port; no retrieval SQL changed, "
                + "no fusion code written (the shipped ReciprocalRankFusion runs unchanged)"
                + (armDReranker == null ? ""
                        : "; the bench-scope reranker runs as a pure post-fusion reorder "
                                + "(deterministic, zero API calls, no boundary interaction)")
                + (fusionWeights == null ? ""
                        : "; the fusion runs the shipped PLAN_V2_WEIGHTS per-kind posture "
                                + "(plan §7 — the 4-arg fabric ctor, the shipped serving map, "
                                + "zero new fusion code; pre-registered in the rank-quality-lane "
                                + "charter tranche 3 BEFORE this run)")
                + (notesFloor == 0 ? ""
                        : "; the per-arm EXTERNAL_NOTES floor (N=" + notesFloor
                                + ") runs as a pure pre-boundary arm extension (deterministic "
                                + "probe doubling, zero API calls, no boundary interaction; "
                                + "pre-registered in POOL-COMPOSITION-PREREGISTRATION.md "
                                + "BEFORE this run)")
                + (notesKindArm == 0 ? ""
                        : "; the per-kind EXTERNAL_NOTES arm (K=" + notesKindArm
                                + ") runs as a third pre-boundary fusion input (the kind-scoped "
                                + "production candidate SQL + deterministic chunk_ref ASC "
                                + "tiebreak through the production EmbeddingProvider port, "
                                + "zero API calls, no boundary interaction; pre-registered in "
                                + "ARM-COMPOSITION-PREREGISTRATION.md BEFORE this run)")
                + (notesRankCap == 0 ? ""
                        : "; the kind-arm horizon rank-bound (H=" + notesRankCap
                                + ") runs as a pure post-fusion served-order partition "
                                + "(deterministic, zero API calls, no boundary interaction; "
                                + "pre-registered in RANK-BOUNDED-KIND-ADMISSION-PREREGISTRATION.md "
                                + "BEFORE this run)")
                + (minCosine == 0 ? ""
                        : "; the precision-side cosine trim (theta=" + minCosine
                                + ") runs as a pure post-cut removal filter on the vector arm's "
                                + "candidates (the production MIN_COSINE constant's own filter "
                                + "shape at the bench posture; deterministic, zero API calls, no "
                                + "boundary interaction; the list under-fills and never backfills; "
                                + "pre-registered in MIN-COSINE-PREREGISTRATION.md BEFORE this run)"));
        results.put("fabric", Map.of(
                "providers", notesArm == null
                        ? List.of(semantic.id(), lexical.id())
                        : List.of(semantic.id(), lexical.id(), notesArm.id()),
                "fusion", "ReciprocalRankFusion k=60 (the shipped serving fuser)",
                "fusion_weights", fusionWeights == null
                        ? "unweighted (null map — the recorded r9/d-r1 posture; the 3-arg ctor)"
                        : "PLAN_V2_WEIGHTS (the shipped plan §7 serving posture: NOTE 1.0 / "
                                + "SYLLABUS 0.9 / QUESTION_PAPER 0.8 / TEXTBOOK 0.7 / MARK_SCHEME 0.6 / "
                                + "CARD 0.3; absent sources 1.0; contribution weight/(k+rank+1), k=60)",
                "per_arm_limit", perArmLimit,
                "notes_floor", notesFloor,
                "notes_kind_arm", notesKindArm,
                "notes_rank_cap", notesRankCap,
                "min_cosine", minCosine,
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
                "spec_resolution_axis", hvPresent
                        ? "SCORED for " + armLabel(armDReranker, fusionWeights, notesFloor,
                                notesKindArm, notesRankCap, minCosine)
                        + " on BOTH views (see spec_resolution_hv): the snapshot "
                        + "carries the HUMAN_VALIDATED chunk→SP projection (SNAP5-H1); gate input = "
                        + "the served ALL-denominator view per §10 ruling 1, the compliant view "
                        + "reported alongside"
                        : "NOT SCOREABLE for " + armLabel(armDReranker, fusionWeights, notesFloor,
                                notesKindArm, notesRankCap, minCosine)
                        + ": zero HUMAN_VALIDATED "
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
                "C", "RUNNABLE — this run (hybrid over the production fabric"
                        + (fusionWeights != null
                                ? ", plan-v2 weighted fusion posture — the shipped PLAN_V2_WEIGHTS "
                                        + "map; a posture of arm C, not a new registry row"
                                : "") + ")",
                "D", armDReranker == null
                        ? "UNAVAILABLE — C + reranker behind the EvidenceReranker port"
                        : "RUNNABLE — this run (arm C + " + armDReranker.getClass().getSimpleName()
                                + " behind the EvidenceReranker port, downstream of fusion)",
                "E/F/G", "UNAVAILABLE — require T-C15",
                "H1/H2/H3/I", "UNAVAILABLE — prerequisites unchanged"));
        results.put("per_query_chunks", perQueryServed);
        results.put("per_query_compliant", perQueryCompliant);
        if (hvPresent) {
            results.put("spec_resolution_hv", ChunkSpecHvResolution.section(snapshot,
                    Map.of("served_view_all_denominator", hvAggServed,
                            "compliant_view", ChunkSpecHvResolution.aggregate(hvRowsCompliant)),
                    ChunkSpecHvResolution.unbridgedGoldPoints(hvGoldPoints, hvCodes)));
        }
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
        if (hvPresent) {
            // the second pass re-runs the fabric; recompute §8(d) on the replayed lists
            List<ChunkSpecHvResolution.QueryResolution> hvSecondServed = new ArrayList<>();
            List<ChunkSpecHvResolution.QueryResolution> hvSecondCompliant = new ArrayList<>();
            for (BenchGold.GoldRecord rec : gold.records()) {
                if (rec.goldEvidence().isEmpty()) {
                    continue;
                }
                List<String> againServed = refs(servedFabric.retrieve(
                        StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit)));
                hvSecondServed.add(ChunkSpecHvResolution.scoreQuery(rec.id(), againServed,
                        rec.goldSpecPoints(), hvCodes));
                List<String> againCompliant = refs(compliantFabric.retrieve(
                        StructuredRetrievalQuery.of(rec.query(), scope, perArmLimit)));
                hvSecondCompliant.add(ChunkSpecHvResolution.scoreQuery(rec.id(), againCompliant,
                        rec.goldSpecPoints(), hvCodes));
            }
            checkEquals(stable.writeValueAsString(ChunkSpecHvResolution.aggregate(hvSecondServed)),
                    stable.writeValueAsString(ChunkSpecHvResolution.aggregate(hvRowsServed)),
                    "§8(d) served view");
            checkEquals(stable.writeValueAsString(ChunkSpecHvResolution.aggregate(hvSecondCompliant)),
                    stable.writeValueAsString(ChunkSpecHvResolution.aggregate(hvRowsCompliant)),
                    "§8(d) compliant view");
        }
        results.put("determinism_check", "PASS — scoring recomputed twice in-process (second "
                + "full retrieval pass), both views' aggregates byte-identical; serialization "
                + "byte-stable");

        // Kind-arm under-fill (T-C72): recorded honestly AFTER the determinism
        // pass (the tracked set is idempotent across the double-pass — same
        // query texts). Fires only at kind-universe exhaustion.
        if (notesArm != null) {
            results.put("notes_kind_arm_underfill", Map.of(
                    "underfilled_queries", notesArm.underFilledQueries(),
                    "note", "queries whose kind-arm list came up short of K — fires only "
                            + "at kind-universe exhaustion (fewer in-scope EXTERNAL_NOTES "
                            + "chunks than K); tracked by stripped query text, idempotent "
                            + "across the determinism double-pass"));
        }

        // Rank-cap under-fill (T-C77): recorded honestly AFTER the determinism
        // pass — queries whose two-arm surface came up shorter than the
        // protected horizon (kind-arm-only refs then serve inside 1..H; the
        // partition's guarantee is conditional, the recording is not).
        if (servedRankCap != null) {
            results.put("notes_rank_cap_underfill", Map.of(
                    "horizon", notesRankCap,
                    "served_queries", servedRankCap.underFilledQueries(),
                    "compliant_queries", compliantRankCap.underFilledQueries(),
                    "note", "queries whose two-arm surface came up shorter than the protected "
                            + "horizon H (kind-arm-only refs then serve inside 1..H — recorded "
                            + "honestly, never patched; tracked by stripped query text, "
                            + "idempotent across the determinism double-pass)"));
        }

        // Min-cosine removal ledger (T-C83): recorded honestly AFTER the
        // determinism pass — the idempotent per-query removal counts (the
        // tracked set is keyed by stripped query text, so the served view,
        // the compliant view and the double-pass all reconcile to one
        // per-query ledger). The under-fill IS the removal — never patched.
        if (minCosineProvider != null) {
            results.put("min_cosine_removed", Map.of(
                    "theta", minCosineProvider.minCosine(),
                    "affected_queries", minCosineProvider.affectedQueries(),
                    "removed_total", minCosineProvider.removedTotal(),
                    "note", "vector-arm candidates removed by the post-cut cosine trim, per "
                            + "the idempotent per-query ledger (distinct queries affected + "
                            + "total removals; the list under-fills and never backfills — "
                            + "the T-C83 pre-registration's removal-only shape)"));
        }

        // ── 8. write evidence (results.json + RUN_REPORT.md + SHA256SUMS) ────
        Files.createDirectories(runOut);
        String pretty = stable.writerWithDefaultPrettyPrinter().writeValueAsString(results);
        Files.writeString(runOut.resolve("results.json"), pretty, StandardCharsets.UTF_8);
        Files.writeString(runOut.resolve("RUN_REPORT.md"),
                report(runDate, results, servedOverall, compliantOverall, servedPerClass,
                        compliantPerClass, labeled.size(), noLabelIds.size(), zeroResultQueries,
                        compliantStarved, violations, dbChunks, validatedCorpus, context,
                        manifest, gate, perArmLimit, notesFloor, notesKindArm,
                        notesArm == null ? 0 : notesArm.underFilledQueries(),
                        notesRankCap,
                        (servedRankCap == null ? 0 : servedRankCap.underFilledQueries())
                                + (compliantRankCap == null ? 0
                                        : compliantRankCap.underFilledQueries()),
                        minCosine,
                        minCosineProvider == null ? 0L : minCosineProvider.removedTotal(),
                        armDReranker, fusionWeights),
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

    /** §8 v1.1 arithmetic (spec §8.1, T-C40 ②): (a)(b)(c) evaluated on the ALL denominator
     *  = served view against the v1.1 ALL bars (ruling 1 unchanged), PLUS the same three axes
     *  evaluated on the compliant (VALIDATED-gated) view against the v1.1 VALIDATED bars —
     *  an ALL-pass whose compliant view fails is the validation boundary converting the
     *  learner's serving pool into the weaker one, and it blocks promotion, with a note, not
     *  a waiver. v1.0 floors retained as a reference column; verdicts already recorded under
     *  v1.0 are NOT re-judged. */
    private static Map<String, Object> gateArithmetic(Map<String, Object> servedOverall,
                                                      Map<String, Object> compliantOverall,
                                                      int violations,
                                                      Map<String, Object> hvAggServed) {
        double recall10 = asDouble(servedOverall.get("recall@10"));
        double mrr = asDouble(servedOverall.get("mrr"));
        double ndcg10 = asDouble(servedOverall.get("ndcg@10"));
        double vRecall10 = asDouble(compliantOverall.get("recall@10"));
        double vMrr = asDouble(compliantOverall.get("mrr"));
        double vNdcg10 = asDouble(compliantOverall.get("ndcg@10"));
        boolean a = recall10 >= GATE_V11_ALL_RECALL10;
        boolean b = mrr >= GATE_V11_ALL_MRR;
        boolean c = ndcg10 >= GATE_V11_ALL_NDCG10;
        boolean va = vRecall10 >= GATE_V11_VALIDATED_RECALL10;
        boolean vb = vMrr >= GATE_V11_VALIDATED_MRR;
        boolean vc = vNdcg10 >= GATE_V11_VALIDATED_NDCG10;
        boolean f = violations == 0;
        boolean promoted = a && b && c && va && vb && vc && f;
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("version", "spec §8 v1.1 re-index (2026-10-01, T-C40 ②, §8.1): dual-view bars from "
                + "run-006-bproxy (snap-006 × gold-v5), B-proxy +10% relative / +0.05 absolute per view "
                + "(ALL 0.1745→0.1920, 0.0737→0.1237, 0.1816→0.2316 · VALIDATED 0.0667→0.0734, "
                + "0.0684→0.1184, 0.1183→0.1683); v1.0 lines retained as reference (0.2954→0.3249, "
                + "0.2464→0.2964, 0.4299→0.4799); no recorded verdict re-judged");
        gate.put("evaluated_on", "served view (ALL denominator, v1.1 ALL bars) + compliant view "
                + "(v1.1 VALIDATED bars); promotion requires BOTH");
        gate.put("a_recall@10", Map.of("value", recall10, "floor", GATE_V11_ALL_RECALL10, "pass", a));
        gate.put("b_mrr", Map.of("value", mrr, "floor", GATE_V11_ALL_MRR, "pass", b));
        gate.put("c_ndcg@10", Map.of("value", ndcg10, "floor", GATE_V11_ALL_NDCG10, "pass", c));
        gate.put("a2_validated_recall@10", Map.of("value", vRecall10, "floor", GATE_V11_VALIDATED_RECALL10, "pass", va));
        gate.put("b2_validated_mrr", Map.of("value", vMrr, "floor", GATE_V11_VALIDATED_MRR, "pass", vb));
        gate.put("c2_validated_ndcg@10", Map.of("value", vNdcg10, "floor", GATE_V11_VALIDATED_NDCG10, "pass", vc));
        gate.put("v1_0_reference", Map.of(
                "a_recall@10", Map.of("floor", GATE_RECALL10, "pass", recall10 >= GATE_RECALL10),
                "b_mrr", Map.of("floor", GATE_MRR, "pass", mrr >= GATE_MRR),
                "c_ndcg@10", Map.of("floor", GATE_NDCG10, "pass", ndcg10 >= GATE_NDCG10)));
        if (hvAggServed != null && !hvAggServed.isEmpty()) {
            gate.put("d_spec_resolution", "SCORED (spec_resolution_hv): full-coverage "
                    + hvAggServed.get("spec_points_full_coverage_rate") + " · micro-average "
                    + hvAggServed.get("spec_points_micro_average") + " on the ALL-denominator view over "
                    + hvAggServed.get("gold_points_total") + " gold points — first §8(d)-scoreable run: "
                    + "this run SETS the chunk-arm baseline; the §8(d) 'no regression beyond 1pp' "
                    + "rule applies from the next run onward, and no promotion claim is made on (d) here");
        } else {
            gate.put("d_spec_resolution", "NOT SCOREABLE — zero HUMAN_VALIDATED chunk→SP rows "
                    + "(named data gap; nothing to regress, nothing to claim)");
        }
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

    /** The registry-facing arm label for the postures this orchestrator measures. */
    private static String armLabel(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights,
                                   int notesFloor, int notesKindArm, int notesRankCap,
                                   double minCosine) {
        if (reranker != null) {
            return "arm D (arm C + reranker)";
        }
        if (fusionWeights != null) {
            return "arm C (plan-v2 weighted fusion posture)";
        }
        if (notesFloor > 0) {
            return "arm C (per-arm notes floor N=" + notesFloor + ")";
        }
        if (notesRankCap > 0) {
            return "arm C (per-kind notes arm K=" + notesKindArm
                    + ", rank-bounded H=" + notesRankCap + ")";
        }
        if (minCosine > 0) {
            return "arm C (precision-side cosine trim theta=" + minCosine + ")";
        }
        return notesKindArm > 0
                ? "arm C (per-kind notes arm K=" + notesKindArm + ")"
                : "arm C";
    }

    /**
     * Fusion-weights spec parser (T-C65, charter tranche 3): the shipped
     * PLAN_V2_WEIGHTS posture behind {@code BENCH_FUSION_WEIGHTS}. Absent or
     * blank = the unweighted 3-arg posture (the recorded r9/d-r1 basis,
     * byte-identical by construction); {@code plan_v2} = the shipped map; an
     * unknown spec fails closed — never silently unweighted.
     */
    static Map<EvidenceItem.EvidenceSource, Double> fusionWeightsForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        if ("plan_v2".equals(spec.trim())) {
            return RetrievalFabric.PLAN_V2_WEIGHTS;
        }
        throw new IllegalStateException("unknown BENCH_FUSION_WEIGHTS spec: '" + spec
                + "' (known: plan_v2) — fail-closed");
    }

    /**
     * One lever at a time (attribution honesty, the T-C65 pre-registration):
     * the reranker and the fusion-weights postures are DIFFERENT levers with
     * different mechanisms — enabling both in one run would confound the
     * attribution the harness exists to record. Fail-closed.
     */
    static void requireSingleLever(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights) {
        requireSingleLever(reranker, fusionWeights, 0);
    }

    /** Three-way one-lever guard (T-C65 pair extended by the T-C69 floor). */
    static void requireSingleLever(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights,
                                   int notesFloor) {
        requireSingleLever(reranker, fusionWeights, notesFloor, 0);
    }

    /** Four-way one-lever guard (T-C65 pair + T-C69 floor + T-C72 kind-arm). */
    static void requireSingleLever(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights,
                                   int notesFloor, int notesKindArm) {
        requireSingleLever(reranker, fusionWeights, notesFloor, notesKindArm, 0);
    }

    /**
     * Five-way one-lever guard (T-C65 pair + T-C69 floor + T-C72 kind-arm +
     * T-C77 rank-bound): the tranche's lever is the BOUND on the recorded
     * kind-arm posture, so the kind-arm and its rank-bound count as ONE lever
     * — but a bound with no bounded arm is a composition error, and the bound
     * remains mutually exclusive with the floor x reranker x weights levers.
     * Fail-closed (the T-C77 pre-registration, §4).
     */
    static void requireSingleLever(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights,
                                   int notesFloor, int notesKindArm, int notesRankCap) {
        requireSingleLever(reranker, fusionWeights, notesFloor, notesKindArm, notesRankCap, 0);
    }

    /**
     * Six-way one-lever guard (T-C65 pair + T-C69 floor + T-C72 kind-arm +
     * T-C77 rank-bound + T-C83 min-cosine): the min-cosine trim is a lever
     * with its own mechanism (candidate REMOVAL from the vector arm) and is
     * mutually exclusive with every other posture — a trim composed with any
     * other lever would confound the attribution the harness exists to
     * record. Fail-closed (the T-C83 pre-registration, §4).
     */
    static void requireSingleLever(EvidenceReranker reranker,
                                   Map<EvidenceItem.EvidenceSource, Double> fusionWeights,
                                   int notesFloor, int notesKindArm, int notesRankCap,
                                   double minCosine) {
        if (notesRankCap > 0 && notesKindArm <= 0) {
            throw new IllegalStateException("BENCH_NOTES_RANK_CAP requires BENCH_NOTES_KIND_ARM "
                    + "(a bound with no bounded arm is a composition error — fail-closed, "
                    + "the T-C77 pre-registration)");
        }
        int active = (reranker != null ? 1 : 0) + (fusionWeights != null ? 1 : 0)
                + (notesFloor > 0 ? 1 : 0)
                + ((notesKindArm > 0 || notesRankCap > 0) ? 1 : 0)
                + (minCosine > 0 ? 1 : 0);
        if (active > 1) {
            throw new IllegalStateException("BENCH_ARM_D_RERANKER, BENCH_FUSION_WEIGHTS, "
                    + "BENCH_NOTES_FLOOR, BENCH_NOTES_KIND_ARM (with its T-C77 rank-bound "
                    + "BENCH_NOTES_RANK_CAP) and BENCH_MIN_COSINE (the T-C83 precision-side "
                    + "trim) are mutually exclusive (one lever at a time — "
                    + "the T-C65/T-C69/T-C72/T-C77/T-C83 pre-registrations' attribution guard)");
        }
    }

    /**
     * Provider composition (T-C72): the kind-arm appends as the THIRD arm
     * input when active; the absent path returns the recorded two-arm list
     * unchanged (absent-path byte-identity by construction).
     */
    static List<RetrievalProvider> composeArms(RetrievalProvider semantic,
                                               RetrievalProvider lexical,
                                               RetrievalProvider notesArm) {
        return notesArm == null
                ? List.of(semantic, lexical)
                : List.of(semantic, lexical, notesArm);
    }

    // ── RUN_REPORT.md ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String report(String runDate, Map<String, Object> results,
                                 Map<String, Object> servedOverall, Map<String, Object> compliantOverall,
                                 Map<String, Object> servedPerClass, Map<String, Object> compliantPerClass,
                                 int labeledN, int excludedN, int zeroResultQueries, int compliantStarved,
                                 int violations, int corpusN, long validatedCorpus,
                                 Map<String, Object> context, JsonNode manifest,
                                 Map<String, Object> gate, int perArmLimit, int notesFloor,
                                 int notesKindArm, int kindArmUnderfilled, int notesRankCap,
                                 int rankCapUnderfill,
                                 double minCosine, long minCosineRemoved,
                                 EvidenceReranker armDReranker,
                                 Map<EvidenceItem.EvidenceSource, Double> fusionWeights) {
        String armLetter = armDReranker == null ? "C" : "D";
        StringBuilder md = new StringBuilder();
        md.append(armDReranker != null
                ? "# Run 005 — D hybrid+rerank arm (arm C + the pre-registered lexical-precision reranker)\n\n"
                : fusionWeights != null
                ? "# Run 005 — C hybrid arm, plan-v2 weighted fusion posture (the shipped PLAN_V2_WEIGHTS lever)\n\n"
                : notesFloor > 0
                ? "# Run 005 — C hybrid arm, per-arm notes floor posture (the T-C69 per-kind quota lever, floor form)\n\n"
                : notesRankCap > 0
                ? "# Run 005 — C hybrid arm, per-kind notes arm posture, rank-bounded horizon "
                        + "(the T-C77 rank-bound lever, RANK-CAP form)\n\n"
                : minCosine > 0
                ? "# Run 005 — C hybrid arm, precision-side cosine trim posture "
                        + "(the T-C83 MIN_COSINE lever, TRIM form)\n\n"
                : notesKindArm > 0
                ? "# Run 005 — C hybrid arm, per-kind notes arm posture (the T-C72 kind-arm lever, KIND-ARM form)\n\n"
                : "# Run 005 — C hybrid arm, first recorded run (the retrieval fabric orchestrator)\n\n");
        md.append("**Status:** RECORDED — production hybrid arm on record (deterministic, offline ")
                .append("replay, zero API calls; snapshot ").append(results.get("snapshot")).append(").\n");
        md.append("**Arm:** ").append(results.get("arm")).append("\n");
        md.append("**Executor:** ").append(results.get("executor")).append(" — code `")
                .append(results.get("code_version")).append("`.\n");
        md.append("**Date:** ").append(runDate).append(" | **Gold:** ")
                .append(results.get("gold_set")).append(" | ")
                .append("**Determinism:** double retrieval pass, byte-identical aggregates (both views).\n\n");

        md.append("## Fabric provenance\n\n")
                .append("- Orchestrator: the registered gap CLOSED — `RetrievalFabric` composes the ")
                .append("explicit arms [pgvector, bm25] (never injection-implied, E-1 forward note), ")
                .append("applies the serving boundary ONCE centrally pre-fusion, and fuses with the ")
                .append("shipped `ReciprocalRankFusion` k=60 — no new fusion code, no retrieval SQL ")
                .append("changed. Per-arm candidate bound ").append(perArmLimit).append(".\n")
                .append(armDReranker == null ? "" : "- Reranker: `"
                        + armDReranker.getClass().getSimpleName()
                        + "` applied DOWNSTREAM of fusion behind the production EvidenceReranker "
                        + "port (registry row D; pre-registered design, T-C63 charter: BM25-style "
                        + "query×content rescoring, per-query pool IDF, k1=1.2, b=0 — no kind "
                        + "awareness, no gold knowledge; the pre-fusion boundary is untouched).\n")
                .append(fusionWeights == null ? "" : "- Fusion weights: the shipped `PLAN_V2_WEIGHTS` "
                        + "(plan §7 v2 routing stance — NOTE 1.0 / SYLLABUS 0.9 / QUESTION_PAPER 0.8 / "
                        + "TEXTBOOK 0.7 / MARK_SCHEME 0.6 / CARD 0.3; sources absent from the map weigh "
                        + "1.0), applied as weight/(k+rank+1) inside the shipped fuser; within-arm "
                        + "ranks untouched — only cross-source influence scales; pre-registered in "
                        + "FUSION-WEIGHTS-PREREGISTRATION.md BEFORE this run (charter tranche 3).\n")
                .append(notesFloor == 0 ? "" : "- Notes floor: per-arm EXTERNAL_NOTES floor N="
                        + notesFloor + " appended beyond each arm's candidate bound, PRE-boundary "
                        + "(the T-C69 per-kind quota lever, floor form — pure-additive admission: "
                        + "the recorded cut is untouched, the union can only grow, the boundary "
                        + "and the fusion are unchanged; deterministic probe doubling; "
                        + "pre-registered in POOL-COMPOSITION-PREREGISTRATION.md BEFORE this run).\n")
                .append(notesKindArm == 0 ? "" : "- Notes kind arm: per-kind EXTERNAL_NOTES arm K="
                        + notesKindArm + " as a THIRD fusion input, PRE-boundary (the T-C72 "
                        + "kind-arm lever, KIND-ARM form — the kind-scoped production candidate "
                        + "SQL with a deterministic chunk_ref ASC tiebreak through the production "
                        + "EmbeddingProvider port: notes compete only against notes, the existing "
                        + "arms' lists byte-unchanged, the shipped unweighted fusion and the "
                        + "central boundary as shipped; under-fill " + kindArmUnderfilled
                        + " queries at kind-universe exhaustion; pre-registered in "
                        + "ARM-COMPOSITION-PREREGISTRATION.md BEFORE this run).\n")
                .append(notesRankCap == 0 ? "" : "- Notes rank cap: kind-arm horizon rank-bound H="
                        + notesRankCap + " applied POST-fusion to BOTH fabrics (the T-C77 "
                        + "rank-bound lever, RANK-CAP form — the two-arm horizon partition: "
                        + "every pool ref carrying an arm A/B contribution orders by its "
                        + "STRIPPED two-arm RRF score, kind-arm-only admissions serve below "
                        + "the entire two-arm surface ordered by kind-arm rank, chunk_ref ASC "
                        + "tiebreaks; the kind-arm admits exactly as recorded — its list, its "
                        + "SET contribution, the pool all unchanged; under-fill "
                        + rankCapUnderfill + " query-views shorter than H; pre-registered in "
                        + "RANK-BOUNDED-KIND-ADMISSION-PREREGISTRATION.md BEFORE this run).\n")
                .append(minCosine == 0 ? "" : "- Min-cosine trim: the vector arm's post-cut candidates "
                        + "filtered to cosine >= " + minCosine + " (the T-C83 precision-side "
                        + "MIN_COSINE lever, TRIM form — the production constant's own post-cut "
                        + "filter shape at the bench posture; REMOVAL-ONLY: the list under-fills "
                        + "and never backfills, the bm25 arm untouched — bm25 has no cosine; "
                        + "survivors' fused scores byte-identical, the whole posture's delta is "
                        + "the removed-ref ledger; " + minCosineRemoved + " candidates removed "
                        + "per the idempotent per-query ledger; pre-registered in "
                        + "MIN-COSINE-PREREGISTRATION.md BEFORE this run).\n")
                .append("- Frozen artifact `").append(manifest.path("run_id").asText()).append("`: model `")
                .append(manifest.path("model").asText()).append("` @ ").append(manifest.path("dimension").asInt())
                .append(" dims; verified fail-closed against this run's frozen inputs before anything ran; ")
                .append("SHA-256 echo in results.json `embedding_artifact.sha256_echo`.\n\n");

        md.append("## Overall (chunk axis, n=").append(labeledN).append(" labeled queries)\n\n");
        md.append("- **").append(armLetter).append(" served (fabric over components as they stand, ALL denominator, ")
                .append(corpusN).append(" embedded chunks):** ").append(fmt(servedOverall)).append("\n");
        md.append("- **").append(armLetter).append(" compliant (central VALIDATED gate pre-fusion, ").append(validatedCorpus)
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

        md.append("## Per class (").append(armLetter).append(" served view)\n\n| class | recall@5 | recall@10 | recall@20 | mrr | ndcg@10 | prec@10 | fp@10 |\n");
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
        md.append("\n## Per class (").append(armLetter).append(" compliant view — T-C05-closed configuration)\n\n")
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

        md.append("\n## S8 gate arithmetic (§8 v1.1 re-index 2026-10-01, spec §8.1; ruling 1: ALL ")
                .append("denominator = served view; VALIDATED bars on the compliant view; v1.0 reference retained)\n\n");
        md.append("- (a) ALL bars, served view — Recall@10: **").append(fmtGate(gate, "a_recall@10")).append("\n");
        md.append("- (b) ALL bars, served view — MRR: **").append(fmtGate(gate, "b_mrr")).append("\n");
        md.append("- (c) ALL bars, served view — nDCG@10: **").append(fmtGate(gate, "c_ndcg@10")).append("\n");
        md.append("- (a2) VALIDATED bars, compliant view — Recall@10: **").append(fmtGate(gate, "a2_validated_recall@10")).append("\n");
        md.append("- (b2) VALIDATED bars, compliant view — MRR: **").append(fmtGate(gate, "b2_validated_mrr")).append("\n");
        md.append("- (c2) VALIDATED bars, compliant view — nDCG@10: **").append(fmtGate(gate, "c2_validated_ndcg@10")).append("\n");
        md.append("- (d) SpecificationPoint resolution: ").append(gate.get("d_spec_resolution")).append("\n");
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
                .append(ChunkSpecHvResolution.reportLine(results,
                        "- SpecificationPoint resolution: NOT SCOREABLE (zero HUMAN_VALIDATED "
                                + "chunk-to-SP mapping rows; T-C06/F-168 substrate pending) — recorded as a "
                                + "named data gap, never fabricated.\n\n"));

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
