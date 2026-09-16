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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * T-C13 harness (spec §4, §6): loads the frozen corpus snapshot (snap-001) and
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

    public record ChunkRef(String reference, String content, String paperState, String kind) {
    }

    public record Edge(String relation, String source, String target) {
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
                    c.path("paper_state").asText("UNKNOWN"), c.path("document_kind").asText("UNKNOWN"));
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
}
