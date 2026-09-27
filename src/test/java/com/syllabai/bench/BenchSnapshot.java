package com.syllabai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.NodeType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * T-C13 harness (spec §4, §6): loads the env-selected frozen corpus snapshot and
 * verifies every file against the manifest's SHA-256 before anything runs —
 * fail-closed, the same discipline as the gold-set loader. The snapshot is the
 * ONLY corpus this harness scores against; production is never touched
 * (read-only DB access was used only to BUILD the snapshot, per spec §4).
 *
 * <p>Also adapts the snapshot's graph projection to the production
 * {@code KnowledgeNode} model with deterministic ids (UUID v3 over
 * {@code "bench:" + code}), so the REAL production retriever can run over the
 * frozen data without a database.</p>
 */
public final class BenchSnapshot {

    /**
     * One frozen chunk row. {@code subjectId} is the optional {@code subject_id}
     * projection (absent in snap-001..003; the at-flip generation's exporter
     * projects it so the post-flip freeze carries the card axis' subject
     * identity). It is verification/census evidence for the serving-flip
     * generation — the bench container stamps every chunk with the bench
     * scope's own subject (single-subject topology, Run003B.loadSnapshot), so
     * this field is never a gate input inside the container.
     */
    public record ChunkRef(String reference, String content, String paperState, String kind,
                           String paperCode, String subjectId) {
    }

    public record Edge(String relation, String source, String target) {
    }

    /**
     * Census of the optional {@code chunk_spec_hv.json} projection (the §8(d)
     * chunk→SP HUMAN_VALIDATED substrate, staged for snap-005 by the
     * chunk-sp-substrate-2026-09-27 bridge). Reported verbatim in run output —
     * the honest denominators next to any §8(d) number.
     */
    public record ChunkSpecHvCensus(int rows, int rowsWithRefs, int distinctRefs,
                                    int distinctCodes, List<String> missCodes) {
    }

    public record SpecPoint(String code, String nodeType, String title, String validationStatus) {
    }

    private final String snapshotVersion;
    private final Map<String, ChunkRef> chunksByRef = new LinkedHashMap<>();
    private final Map<String, KnowledgeNode> nodeByCode = new LinkedHashMap<>();
    private final List<KnowledgeNode> structureNodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<Edge>> edgesFrom = new LinkedHashMap<>();
    private final Map<String, List<Edge>> edgesTo = new LinkedHashMap<>();
    private final int anchorCount;
    private final boolean chunkSpecHvPresent;
    private final Map<String, Set<String>> hvCodesByRef = new LinkedHashMap<>();
    private final ChunkSpecHvCensus chunkSpecHvCensus;

    /** Gold spec-point code shape (harness spec §3.2). */
    private static final Pattern SPEC_CODE = Pattern.compile("^4CH1-[0-9]+\\.[0-9A-Za-z]+$");

    private BenchSnapshot(Path dir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(Files.readAllBytes(dir.resolve("manifest.json")));
        this.snapshotVersion = manifest.path("snapshot_version").asText();
        JsonNode hashes = manifest.path("files_sha256");
        List<String> names = new ArrayList<>();
        hashes.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            verify(dir.resolve(name), hashes.path(name).asText(), name);
        }
        int declaredChunks = manifest.path("counts").path("chunks").asInt(-1);

        JsonNode chunks = readGzipJson(dir.resolve("chunks.jsonl.gz"), mapper);
        for (JsonNode c : chunks) {
            ChunkRef chunk = new ChunkRef(c.path("chunk_ref").asText(), c.path("content").asText(""),
                    c.path("paper_state").asText("UNKNOWN"),
                    // the manifests record the kind under "kind" (a "document_kind" key never
                    // existed in the snapshots; nothing consumed kind before T-C14's loader)
                    c.path("kind").asText(c.path("document_kind").asText("UNKNOWN")),
                    c.path("paper_code").asText(null),
                    // optional subject_id projection (missing node and JSON null both fold
                    // to null) — snap-001..003 carry no such field; the at-flip
                    // generation's exporter projects it (see the ChunkRef javadoc)
                    c.path("subject_id").asText(null));
            chunksByRef.put(chunk.reference(), chunk);
        }
        if (declaredChunks >= 0 && chunksByRef.size() != declaredChunks) {
            throw new IllegalStateException("snapshot manifest declares " + declaredChunks
                    + " chunks but file holds " + chunksByRef.size());
        }

        for (JsonNode sp : mapper.readTree(Files.readAllBytes(dir.resolve("spec_points.json")))) {
            SpecPoint point = new SpecPoint(sp.path("code").asText(), sp.path("node_type").asText(),
                    sp.path("title").asText(), sp.path("validation_status").asText());
            KnowledgeNode node = newNode(point.code(), NodeType.valueOf(point.nodeType()),
                    point.title(), point.validationStatus());
            nodeByCode.put(point.code(), node);
            structureNodes.add(node);
        }

        for (JsonNode mis : mapper.readTree(Files.readAllBytes(dir.resolve("misconceptions.json")))) {
            String code = mis.path("code").asText();
            nodeByCode.put(code, newNode(code, NodeType.MISCONCEPTION,
                    mis.path("title").asText(), "VALIDATED"));
        }

        for (JsonNode e : mapper.readTree(Files.readAllBytes(dir.resolve("graph_edges.json")))) {
            Edge edge = new Edge(e.path("relation").asText(), e.path("source").asText(),
                    e.path("target").asText());
            edges.add(edge);
            edgesFrom.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            edgesTo.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            // Placeholder entities for endpoints outside spec points/misconceptions
            // (e.g. CONCEPT codes): they never match (the retriever matches VALIDATED
            // structure nodes only) but keep traversal total. Production state of
            // concept nodes is SUGGESTED — mirrored here.
            nodeByCode.computeIfAbsent(edge.source(), code ->
                    newNode(code, NodeType.CONCEPT, "", "SUGGESTED"));
            nodeByCode.computeIfAbsent(edge.target(), code ->
                    newNode(code, NodeType.CONCEPT, "", "SUGGESTED"));
        }

        JsonNode anchors = mapper.readTree(Files.readAllBytes(dir.resolve("question_anchors.json")));
        this.anchorCount = anchors.size();

        // ── optional §8(d) substrate: chunk→SP HUMAN_VALIDATED mappings ──────
        // snap-001..004 carry no such file (the accessor stays empty and the
        // runner reports §8(d) NOT SCOREABLE exactly as before — the recorded
        // generations keep byte-identical behavior). When the file exists it
        // MUST be manifest-pinned (fail-closed: unpinned bytes never score) —
        // the manifest loop above has already SHA-verified it by the time we
        // get here. Row guards encode the §5 counting rule: only rows whose
        // provenance.validation_status is HUMAN_VALIDATED may load, and a
        // RULE_DERIVED tier with a promoted HV status is exactly what counts.
        Path hvFile = dir.resolve("chunk_spec_hv.json");
        if (!Files.exists(hvFile)) {
            this.chunkSpecHvPresent = false;
            this.chunkSpecHvCensus = new ChunkSpecHvCensus(0, 0, 0, 0, List.of());
        } else {
            if (!hashes.has("chunk_spec_hv.json")) {
                throw new IllegalStateException("chunk_spec_hv.json present but not "
                        + "manifest-pinned (fail-closed: unpinned bytes never score)");
            }
            this.chunkSpecHvPresent = true;
            this.chunkSpecHvCensus = loadChunkSpecHv(mapper.readTree(Files.readAllBytes(hvFile)));
        }
    }

    /** Parses + guards the §8(d) projection; every violation fails closed. */
    private ChunkSpecHvCensus loadChunkSpecHv(JsonNode hv) {
        JsonNode rows = hv.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalStateException("chunk_spec_hv.json carries no rows (fail-closed)");
        }
        Set<String> seenMappingIds = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        List<String> missCodes = new ArrayList<>();
        int rowsWithRefs = 0;
        for (JsonNode r : rows) {
            String mappingId = r.path("mapping_id").asText("");
            if (mappingId.isEmpty() || !seenMappingIds.add(mappingId)) {
                throw new IllegalStateException("chunk_spec_hv.json row has blank or duplicate "
                        + "mapping_id: '" + mappingId + "' (fail-closed)");
            }
            String code = r.path("spec_code").asText("");
            if (!SPEC_CODE.matcher(code).matches()) {
                throw new IllegalStateException("chunk_spec_hv.json row " + mappingId
                        + " has malformed spec_code: '" + code + "' (fail-closed)");
            }
            String validationStatus = r.path("provenance").path("validation_status").asText("");
            if (!"HUMAN_VALIDATED".equals(validationStatus)) {
                // §5 counting rule in code: AI_SUGGESTED / RULE_DERIVED mappings
                // never count as resolution — a non-HV row must abort, not filter.
                throw new IllegalStateException("chunk_spec_hv.json row " + mappingId
                        + " is not HUMAN_VALIDATED (provenance.validation_status='" + validationStatus
                        + "'; fail-closed)");
            }
            List<String> refs = new ArrayList<>();
            for (JsonNode ref : r.path("chunk_refs")) {
                String refStr = ref.asText();
                if (!chunksByRef.containsKey(refStr)) {
                    throw new IllegalStateException("chunk_spec_hv.json row " + mappingId
                            + " references unknown chunk_ref: '" + refStr
                            + "' (fail-closed: the drift gate owns subset-ness, the loader owns existence)");
                }
                refs.add(refStr);
            }
            if (refs.isEmpty()) {
                // a MISS row is recorded, never force-matched (bridge contract);
                // it contributes no refs and surfaces in the census as a miss
                missCodes.add(code);
            } else {
                rowsWithRefs++;
            }
            codes.add(code);
            for (String refStr : refs) {
                hvCodesByRef.computeIfAbsent(refStr, k -> new LinkedHashSet<>()).add(code);
            }
        }
        return new ChunkSpecHvCensus(rows.size(), rowsWithRefs, hvCodesByRef.size(),
                codes.size(), List.copyOf(missCodes));
    }

    private static void verify(Path file, String expectedSha, String name) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalStateException("snapshot file missing: " + name + " (fail-closed)");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha)) {
            throw new IllegalStateException("snapshot file " + name + " SHA-256 mismatch (fail-closed): "
                    + actual + " != " + expectedSha);
        }
    }

    private static JsonNode readGzipJson(Path file, ObjectMapper mapper) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(file))) {
            return mapper.readTree(gzip);
        }
    }

    /**
     * Deterministic id assignment: the production entity assigns ids at persist
     * time; the harness needs stable ids for fusion keys, so the id field is set
     * reflectively to UUID v3 over a bench namespace + code. Same input always
     * yields the same id (run-reproducibility contract, spec §6).
     */
    private static KnowledgeNode newNode(String code, NodeType type, String title, String status) {
        KnowledgeNode node = new KnowledgeNode(code, type, title, null,
                KnowledgeNode.ValidationStatus.valueOf(status), "bench:snap-001", "bench");
        try {
            java.lang.reflect.Field id = KnowledgeNode.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(node, UUID.nameUUIDFromBytes(("bench:snap-001:" + code)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot assign deterministic node id", e);
        }
        return node;
    }

    public static BenchSnapshot load(Path dir) throws IOException {
        return new BenchSnapshot(dir);
    }

    public String snapshotVersion() {
        return snapshotVersion;
    }

    public int chunkCount() {
        return chunksByRef.size();
    }

    public int anchorCount() {
        return anchorCount;
    }

    public int edgeCount() {
        return edges.size();
    }

    public Map<String, ChunkRef> chunks() {
        return chunksByRef;
    }

    public List<KnowledgeNode> structureNodes() {
        return List.copyOf(structureNodes);
    }

    public KnowledgeNode node(String code) {
        return nodeByCode.get(code);
    }

    public KnowledgeNode nodeById(UUID id) {
        for (KnowledgeNode n : nodeByCode.values()) {
            if (id.equals(n.id())) {
                return n;
            }
        }
        return null;
    }

    /** Outgoing edges of a node code (by snapshot relation string, unfiltered). */
    public List<Edge> edgesFrom(String code) {
        return edgesFrom.getOrDefault(code, List.of());
    }

    /** Incoming edges of a node code. */
    public List<Edge> edgesTo(String code) {
        return edgesTo.getOrDefault(code, List.of());
    }

    /**
     * Whether the snapshot carries the §8(d) chunk→SP HUMAN_VALIDATED
     * projection. False for snap-001..004 — the runner must report §8(d) NOT
     * SCOREABLE in that case, exactly as the recorded generations did.
     */
    public boolean chunkSpecHvPresent() {
        return chunkSpecHvPresent;
    }

    /**
     * The §8(d) scoring substrate: chunk_ref → the set of spec codes whose
     * HUMAN_VALIDATED mapping that chunk anchors. Counting predicate is the
     * promoted validation_status, NOT the rule tier (the projection rows are
     * RULE_DERIVED-origin, operator-promoted HV — a tier check would silently
     * zero the metric). Empty unless {@link #chunkSpecHvPresent()} is true.
     * Many-to-many by design: one chunk can anchor several spec codes.
     */
    public Map<String, Set<String>> hvSpecCodesByChunkRef() {
        return Collections.unmodifiableMap(hvCodesByRef);
    }

    /** Census of the §8(d) projection as loaded (zeros when absent). */
    public ChunkSpecHvCensus chunkSpecHvCensus() {
        return chunkSpecHvCensus;
    }
}
