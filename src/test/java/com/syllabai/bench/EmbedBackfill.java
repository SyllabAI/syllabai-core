package com.syllabai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.EmbeddingConfig;
import com.syllabai.content.EmbeddingProperties;
import com.syllabai.content.EmbeddingProvider;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Embedding backfill runner (T-C13 arm A prerequisite; session 92). Computes
 * chunk embeddings for the frozen snap-001 corpus through the PRODUCTION
 * embedding path — the real {@code EmbeddingProvider} bean built by
 * {@code EmbeddingConfig} (same fail-fast dimension contract, same model
 * wiring), stored through the real {@link ChunkVectorRepository#storeEmbedding}
 * — and freezes the result as a checksummed artifact so every later benchmark
 * run replays it offline, byte-stable, with zero further API calls
 * (compute-once-freeze-forever, the same discipline as the corpus snapshot).
 *
 * <p>Model note: the codebase default {@code text-embedding-004} is RETIRED
 * (404 on both v1 and v1beta embedContent, probed 2026-09-17 with a valid
 * key). The successor {@code gemini-embedding-001} at
 * {@code outputDimensionality=768} is dimension-compatible with the V11
 * {@code vector(768)} column and is passed explicitly here; the production
 * default repair is registered separately (TODO row) and is NOT part of this
 * benchmark-only slice.</p>
 *
 * <p>Provenance: vectors are written with {@code embedding_model} + the
 * artifact manifest carries model, dimension, task types, snapshot/gold
 * checksums and the core commit, so a replay can verify it is applying exactly
 * the recorded artifact. Idempotent + resumable by construction: only chunks
 * with {@code embedding IS NULL} are embedded, the DB is the resume state, and
 * the artifact is always dumped from the DB (what is actually stored), never
 * from call-memory. Embedding is index preparation, not serving — the T-C07
 * scope and T-C05 VALIDATED boundaries apply at query time, unchanged.</p>
 *
 * <p>Usage (mirrors Run003B env contract):
 * <pre>
 *   BENCH_JDBC_URL=... BENCH_JDBC_USER=... BENCH_JDBC_PASSWORD=... \
 *   BENCH_SNAPSHOT=&lt;snap-001 dir&gt; BENCH_GOLD=&lt;gold dir&gt; \
 *   BENCH_RUN_OUT=&lt;artifact out dir&gt; BENCH_CORE_COMMIT=&lt;sha&gt; \
 *   SYLLABAI_EMBEDDING_GEMINI_API_KEY=... \
 *   [EMBED_MODEL=gemini-embedding-001] [EMBED_MIN_INTERVAL_MS=600] \
 *   java -cp ... com.syllabai.bench.EmbedBackfill
 * </pre></p>
 */
public final class EmbedBackfill {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EmbedBackfill() {
    }

    public static void main(String[] args) throws Exception {
        String url = required("BENCH_JDBC_URL");
        String user = required("BENCH_JDBC_USER");
        String pass = required("BENCH_JDBC_PASSWORD");
        Path snapshotDir = Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot"));
        Path goldDir = Path.of(env("BENCH_GOLD", "bench/inputs/gold"));
        Path outDir = Path.of(env("BENCH_RUN_OUT", "bench/out/embed-backfill"));
        String apiKey = required("SYLLABAI_EMBEDDING_GEMINI_API_KEY");
        String model = env("EMBED_MODEL", "gemini-embedding-001");
        int dimension = Integer.parseInt(env("EMBED_DIMENSION", "768"));
        long minIntervalMs = Long.parseLong(env("EMBED_MIN_INTERVAL_MS", "600"));
        String coreCommit = env("BENCH_CORE_COMMIT", "unrecorded");
        String runDate = env("BENCH_RUN_DATE", LocalDate.now().toString());

        log("loading frozen snapshot (checksum-verified, fail-closed)");
        BenchSnapshot snapshot = BenchSnapshot.load(snapshotDir);

        log("flyway migrate + production schema");
        Flyway.configure().dataSource(url, user, pass).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));

        log("building the production EmbeddingProvider bean (EmbeddingConfig wiring)");
        EmbeddingProvider provider = realProvider(apiKey, model, dimension);

        BackfillResult result = run(jdbc, provider, snapshot, goldDir, outDir,
                model, dimension, minIntervalMs, coreCommit, runDate);

        log(String.format("DONE status=%s chunks_embedded=%d pending_after=%d queries=%d model=%s out=%s",
                result.status(), result.chunksEmbedded(), result.pendingAfter(),
                result.queriesEmbedded(), model, outDir));
        if (!"COMPLETE".equals(result.status())) {
            System.exit(3); // INCOMPLETE — resume by re-dispatch; artifact + DB state are safe
        }
    }

    /** Injectable core — the IT drives this with a fake provider over a tiny corpus. */
    static BackfillResult run(JdbcTemplate jdbc, EmbeddingProvider provider, BenchSnapshot snapshot,
                              Path goldDir, Path outDir, String model, int dimension,
                              long minIntervalMs, String coreCommit, String runDate) throws Exception {
        Instant generatedAt = Instant.now();
        Files.createDirectories(outDir);

        // ── 1. corpus load (fresh DB) or consistency check (resume) ─────────
        int dbChunks = jdbc.queryForObject("select count(*) from document_chunks", Integer.class);
        int expectedChunks = snapshot == null ? -1 : readSnapshotChunkCount();
        if (dbChunks == 0) {
            if (snapshot == null) {
                throw new IllegalStateException("empty document_chunks and no snapshot provided — nothing to embed");
            }
            log("empty schema — loading snap-001 corpus through the production loader");
            Run003B.loadSnapshot(jdbc, snapshot);
            dbChunks = jdbc.queryForObject("select count(*) from document_chunks", Integer.class);
        }
        if (expectedChunks >= 0 && dbChunks != expectedChunks) {
            throw new IllegalStateException("document_chunks row count " + dbChunks
                    + " != snapshot counts.chunks " + expectedChunks + " — refusing to embed a mixed corpus");
        }
        log("corpus rows: " + dbChunks);

        RatePacer pacer = new RatePacer(minIntervalMs);

        // ── 2. chunk pass: pending only (embedding IS NULL), deterministic order
        List<PendingChunk> pending = jdbc.query("""
                select c.id, c.chunk_index, c.content, d.document_id
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is null
                order by d.document_id, c.chunk_index
                """, (rs, i) -> new PendingChunk(rs.getObject("id", UUID.class),
                rs.getString("document_id"), rs.getInt("chunk_index"), rs.getString("content")));
        log("pending chunks: " + pending.size());
        ChunkVectorRepository vectors = new ChunkVectorRepository(jdbc);
        int embeddedNow = 0;
        for (PendingChunk p : pending) {
            float[] v = pacer.call("chunk " + p.documentId() + ":" + p.chunkIndex(),
                    () -> provider.embedDocument(p.content()));
            if (v == null || v.length != dimension) {
                throw new IllegalStateException("provider returned "
                        + (v == null ? "null" : v.length) + " dims, expected " + dimension
                        + " — refusing to store an inconsistent vector");
            }
            if (vectors.storeEmbedding(p.id(), v, model) != 1) {
                throw new IllegalStateException("storeEmbedding updated 0 rows for " + p.id());
            }
            embeddedNow++;
            if (embeddedNow % 100 == 0) {
                log("embedded " + embeddedNow + "/" + pending.size());
            }
        }
        int pendingAfter = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is null", Integer.class);

        // ── 3. query pass (gold queries, RETRIEVAL_QUERY inside the provider) ─
        Map<String, float[]> queryVectors = new LinkedHashMap<>();
        int queries = 0;
        if (goldDir != null && Files.isDirectory(goldDir)) {
            BenchGold gold = BenchGold.load(goldDir);
            for (BenchGold.GoldRecord rec : gold.records()) {
                float[] v = pacer.call("query " + rec.id(), () -> provider.embedQuery(rec.query()));
                if (v == null || v.length != dimension) {
                    throw new IllegalStateException("provider returned "
                            + (v == null ? "null" : v.length) + " dims for query " + rec.id()
                            + ", expected " + dimension);
                }
                queryVectors.put(rec.id(), v);
                queries++;
            }
        }
        log("queries embedded: " + queries);

        // ── 4. artifact dump FROM THE DB (what is actually stored) ───────────
        // vector::real[] preserves exact float4 values (the ::text cast does not).
        List<StoredChunk> stored = jdbc.query("""
                select c.id, c.chunk_index, d.document_id, c.embedding_model, c.embedding::real[] as vec
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is not null
                """, (rs, i) -> {
            java.sql.Array arr = rs.getArray("vec");
            Object[] raw = (Object[]) arr.getArray();
            float[] v = new float[raw.length];
            for (int j = 0; j < raw.length; j++) {
                v[j] = ((Number) raw[j]).floatValue();
            }
            return new StoredChunk(rs.getString("document_id"), rs.getInt("chunk_index"),
                    rs.getObject("id", UUID.class), v, rs.getString("embedding_model"));
        });
        Map<String, StoredChunk> byRef = new TreeMap<>();
        for (StoredChunk s : stored) {
            byRef.put(s.documentId() + ":" + s.chunkIndex(), s);
        }
        for (StoredChunk s : byRef.values()) {
            if (s.vector().length != dimension) {
                throw new IllegalStateException("stored vector for " + s.documentId() + ":"
                        + s.chunkIndex() + " has " + s.vector().length + " dims");
            }
        }

        // ── 5. write artifact (deterministic bytes) ──────────────────────────
        Path chunksFile = outDir.resolve("embeddings_chunks.jsonl");
        StringBuilder sb = new StringBuilder(1 << 20);
        for (Map.Entry<String, StoredChunk> e : byRef.entrySet()) {
            StoredChunk s = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", e.getKey());
            row.put("chunk_id", s.id().toString());
            row.put("model", s.model());
            row.put("v", s.vector());
            sb.append(JSON.writeValueAsString(row)).append('\n');
        }
        Files.writeString(chunksFile, sb.toString(), StandardCharsets.UTF_8);

        Path queriesFile = null;
        if (!queryVectors.isEmpty()) {
            queriesFile = outDir.resolve("embeddings_queries.jsonl");
            StringBuilder qb = new StringBuilder();
            for (Map.Entry<String, float[]> e : queryVectors.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("qid", e.getKey());
                row.put("model", model);
                row.put("v", e.getValue());
                qb.append(JSON.writeValueAsString(row)).append('\n');
            }
            Files.writeString(queriesFile, qb.toString(), StandardCharsets.UTF_8);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", "embed-backfill-snap-001");
        manifest.put("model", model);
        manifest.put("dimension", dimension);
        manifest.put("task_types", Map.of("chunks", "RETRIEVAL_DOCUMENT", "queries", "RETRIEVAL_QUERY"));
        if (snapshot != null) {
            manifest.put("snapshot_version", snapshot.snapshotVersion());
            manifest.put("snapshot_files_sha256", Manifests.filesSha256(snapshotDirOf(snapshot)));
        }
        if (goldDir != null && Files.isDirectory(goldDir)) {
            manifest.put("gold_files_sha256", Manifests.filesSha256(goldDir));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("corpus_chunks", dbChunks);
        counts.put("chunks_embedded_this_run", embeddedNow);
        counts.put("chunks_stored_total", byRef.size());
        counts.put("pending_after", pendingAfter);
        counts.put("queries_embedded", queries);
        manifest.put("counts", counts);
        manifest.put("core_commit", coreCommit);
        manifest.put("run_date", runDate);
        manifest.put("generated_at", generatedAt.toString());
        manifest.put("notes", List.of(
                "compute-once-freeze-forever: benchmark replays apply this artifact offline; no API calls at replay time",
                "text-embedding-004 (codebase default) is RETIRED (404 v1+v1beta, probed with valid key 2026-09-17);"
                        + " gemini-embedding-001 passed explicitly at outputDimensionality=768 — vector(768) schema-compatible",
                "production default repair + model_versions registration registered separately (benchmark-only slice here)",
                "embedding is index preparation, not serving: T-C07 scope + T-C05 VALIDATED boundaries apply at query time"));
        Path manifestFile = outDir.resolve("manifest.json");
        Files.writeString(manifestFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);

        List<Path> hashed = new ArrayList<>();
        hashed.add(chunksFile);
        if (queriesFile != null) {
            hashed.add(queriesFile);
        }
        hashed.add(manifestFile);
        StringBuilder sums = new StringBuilder();
        for (Path f : hashed) {
            sums.append(sha256(f)).append("  ").append(f.getFileName()).append('\n');
        }
        Files.writeString(outDir.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.UTF_8);

        String status = pendingAfter == 0 ? "COMPLETE" : "INCOMPLETE";
        log("artifact written to " + outDir + " (status " + status + ")");
        return new BackfillResult(status, embeddedNow, pendingAfter, queries, byRef.size());
    }

    /** The production bean, built by the production factory (fail-fast dim contract included). */
    static EmbeddingProvider realProvider(String apiKey, String model, int dimension) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(EmbeddingProperties.class,
                () -> new EmbeddingProperties(new EmbeddingProperties.GeminiEmbedding(apiKey, model, dimension, 30)));
        ctx.register(EmbeddingConfig.class);
        ctx.refresh();
        try {
            return ctx.getBean(EmbeddingProvider.class);
        } finally {
            ctx.close();
        }
    }

    private record PendingChunk(UUID id, String documentId, int chunkIndex, String content) {
    }

    private record StoredChunk(String documentId, int chunkIndex, UUID id, float[] vector, String model) {
    }

    record BackfillResult(String status, int chunksEmbedded, int pendingAfter, int queriesEmbedded,
                          int chunksStoredTotal) {
    }

    private static int readSnapshotChunkCount() throws IOException {
        Path manifest = Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot")).resolve("manifest.json");
        JsonNode node = JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        return node.path("counts").path("chunks").asInt(-1);
    }

    private static Path snapshotDirOf(BenchSnapshot snapshot) {
        // BenchSnapshot does not expose its dir; the manifest checksums are re-read
        // from the same env path the caller loaded (single-snapshot assumption is
        // part of the env contract, fail-closed via BenchSnapshot.load itself).
        return Path.of(env("BENCH_SNAPSHOT", "evidence/bench-001/snapshot"));
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Manifest checksum reader (SHA256SUMS lines) for provenance echo. */
    static final class Manifests {
        private Manifests() {
        }

        static Map<String, String> filesSha256(Path dir) throws IOException {
            Path sums = dir.resolve("SHA256SUMS");
            Map<String, String> out = new LinkedHashMap<>();
            if (!Files.exists(sums)) {
                return out;
            }
            for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+", 2);
                out.put(parts[1].trim(), parts[0]);
            }
            return out;
        }
    }

    /**
     * Call pacing + adaptive backoff. Min-interval between call starts (default
     * tuned under the free-tier RPM ceiling); 429/rate errors retry with
     * exponential backoff; a daily-quota exhaustion surfaces as
     * {@link EmbeddingRateException} so the runner dumps a clean INCOMPLETE
     * state and exits 3 (resume = re-dispatch).
     */
    static final class RatePacer {
        private final long minIntervalMs;
        private long lastStart = 0;

        RatePacer(long minIntervalMs) {
            this.minIntervalMs = Math.max(0, minIntervalMs);
        }

        private synchronized void pace() {
            long now = System.currentTimeMillis();
            long wait = lastStart + minIntervalMs - now;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            lastStart = System.currentTimeMillis();
        }

        float[] call(String what, Callable<float[]> c) {
            long backoff = 5_000L;
            for (int attempt = 1; attempt <= 12; attempt++) {
                pace();
                try {
                    return c.call();
                } catch (EmbeddingRateException e) {
                    throw e;
                } catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    String msg = m.toLowerCase();
                    boolean rate = msg.contains("429") || msg.contains("rate") || msg.contains("resource_exhausted")
                            || msg.contains("too many requests");
                    boolean quotaDay = msg.contains("per day") || msg.contains("requests per day")
                            || msg.contains("daily") || msg.contains("rpd");
                    if (quotaDay) {
                        log("daily quota exhausted at " + what + " — surfacing INCOMPLETE (resume by re-dispatch)");
                        throw new EmbeddingRateException("daily quota exhausted: " + m, e);
                    }
                    if (!rate && !msg.contains("500") && !msg.contains("503") && !msg.contains("backend")) {
                        throw new IllegalStateException("embedding call failed at " + what + ": " + m, e);
                    }
                    log("retry " + attempt + "/12 at " + what + " after " + backoff / 1000 + "s ("
                            + m.substring(0, Math.min(140, m.length())) + ")");
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", ie);
                    }
                    backoff = Math.min(backoff * 2, 120_000L);
                }
            }
            throw new EmbeddingRateException("retries exhausted at " + what, null);
        }
    }

    static final class EmbeddingRateException extends RuntimeException {
        EmbeddingRateException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    static void log(String msg) {
        System.out.println("[embed-backfill] " + Instant.now() + " " + msg);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    private static String required(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing required env " + k);
        }
        return v;
    }
}
