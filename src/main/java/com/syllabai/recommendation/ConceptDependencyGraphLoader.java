package com.syllabai.recommendation;

import com.syllabai.recommendation.ConceptDependencyGraph.RawEdge;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the settled T-C11 concept graph snapshot into a
 * {@link ConceptDependencyGraph} at startup.
 *
 * <p><strong>Snapshot contract (2026-09-13, close of T-C11 Batch 4):</strong>
 * {@code classpath:concept-graph/concept_edges.yaml}, {@code concept-graph/concepts.yaml}
 * and {@code concept-graph/practicals.yaml} are byte-verbatim copies of the
 * settled store in the syllabai-resources repo (113 concept nodes + 12 practical
 * nodes / 275 edges / 158 semantic edges, of which 153 HUMAN_VALIDATED; the only
 * non-validated semantic edges are the 3 frozen pilot operator HOLDs and the 2
 * REVIEW_REQUIRED edges). All files are pinned by SHA-256 below: any drift — an
 * edited file, a re-synced store from a later batch — fails startup loudly
 * instead of silently changing recommendation inputs. Upgrading the snapshot
 * is therefore always a conscious, reviewable change: copy the new settled
 * bytes, update the three hashes, and update this comment. (The store files
 * are generated deterministically by the resources repo, so byte-identity is
 * meaningful.) Practical nodes participate because 12 validated
 * REQUIRES_PREREQUISITE edges originate at them — a real pedagogical dependency
 * the settled store carries (the practical requires its underlying concepts).</p>
 *
 * <p>Parsing is safe (SnakeYAML {@link SafeConstructor} — plain data only, the
 * files are static packaged resources, never user input) and PART_OF edges are
 * skipped: curriculum structure and the authoritative spec anchor stay the
 * runtime knowledge graph's job; this layer carries only semantic dependencies.
 * All status filtering and fail-closed integrity checks happen in
 * {@link ConceptDependencyGraph#of} — the single enforcement point shared with
 * tests.</p>
 */
@Component
public class ConceptDependencyGraphLoader {

    private static final Logger log = LoggerFactory.getLogger(ConceptDependencyGraphLoader.class);

    static final String EDGES_RESOURCE = "concept-graph/concept_edges.yaml";
    static final String NODES_RESOURCE = "concept-graph/concepts.yaml";
    static final String PRACTICALS_RESOURCE = "concept-graph/practicals.yaml";

    /** SHA-256 of the settled concept_edges.yaml snapshot (Batch-4 close, 2026-09-13). */
    static final String EDGES_SHA256 =
            "e583ae50916fcb54a924bb13f42625a840e3d9baaec8fa5f69e62122716e5f07";
    /** SHA-256 of the settled concepts.yaml snapshot (Batch-4 close, 2026-09-13). */
    static final String NODES_SHA256 =
            "69cc554c04135188d6c7c44fddd9831f6c86bd7016684f3a15a2c7e1374d5613";
    /** SHA-256 of the settled practicals.yaml snapshot (Batch-4 close, 2026-09-13). */
    static final String PRACTICALS_SHA256 =
            "e53e5f87606a2b5a5b7e534f0375d970498ea85a5655ca32e5bd4526dc9fa528";

    /** The one relation this loader intentionally drops (structure belongs to the KG). */
    private static final String PART_OF = "PART_OF";

    /**
     * Loads and verifies the packaged settled snapshot. Fails fast on missing
     * resources, hash drift, malformed YAML, or any integrity violation raised
     * by {@link ConceptDependencyGraph#of}.
     */
    public ConceptDependencyGraph load() {
        byte[] edgesBytes = readResource(EDGES_RESOURCE);
        byte[] nodesBytes = readResource(NODES_RESOURCE);
        byte[] practicalsBytes = readResource(PRACTICALS_RESOURCE);
        requireSha256(EDGES_RESOURCE, edgesBytes, EDGES_SHA256);
        requireSha256(NODES_RESOURCE, nodesBytes, NODES_SHA256);
        requireSha256(PRACTICALS_RESOURCE, practicalsBytes, PRACTICALS_SHA256);
        return parse(edgesBytes, nodesBytes, practicalsBytes);
    }

    /** Parsed-graph-from-bytes seam (package-private for the tamper tests). */
    ConceptDependencyGraph parse(byte[] edgesBytes, byte[] nodesBytes, byte[] practicalsBytes) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> edgesDoc = yaml.load(new String(edgesBytes, java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> nodesDoc = yaml.load(new String(nodesBytes, java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> practicalsDoc = yaml.load(new String(practicalsBytes, java.nio.charset.StandardCharsets.UTF_8));

        Set<String> nodeCodes = readNodeCodes(nodesDoc, "nodes", NODES_RESOURCE);
        nodeCodes.addAll(readNodeCodes(practicalsDoc, "practicals", PRACTICALS_RESOURCE));
        nodeCodes = java.util.Set.copyOf(nodeCodes);
        List<RawEdge> rawEdges = readRawEdges(edgesDoc);

        ConceptDependencyGraph graph = ConceptDependencyGraph.of(rawEdges, nodeCodes);
        log.info("settled T-C11 concept dependency graph loaded: {} validated semantic edge(s) "
                        + "over {} node code(s) (raw semantic edges incl. frozen non-validated: {})",
                graph.validatedEdgeCount(), nodeCodes.size(), rawEdges.size());
        return graph;
    }

    // ── internals ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Set<String> readNodeCodes(Map<String, Object> nodesDoc, String listKey, String resource) {
        Object nodes = nodesDoc == null ? null : nodesDoc.get(listKey);
        if (!(nodes instanceof List<?> nodeList) || nodeList.isEmpty()) {
            throw new IllegalStateException("concept graph snapshot: no nodes in " + resource);
        }
        Set<String> codes = new HashSet<>();
        for (Object node : nodeList) {
            if (!(node instanceof Map<?, ?> m) || !(m.get("code") instanceof String code) || code.isBlank()) {
                throw new IllegalStateException(
                        "concept graph snapshot: node without a code in " + resource);
            }
            if (!codes.add(code)) {
                throw new IllegalStateException(
                        "concept graph snapshot: duplicate node code '" + code + "'");
            }
        }
        return codes;
    }

    @SuppressWarnings("unchecked")
    private static List<RawEdge> readRawEdges(Map<String, Object> edgesDoc) {
        Object edges = edgesDoc == null ? null : edgesDoc.get("edges");
        if (!(edges instanceof List<?> edgeList)) {
            throw new IllegalStateException("concept graph snapshot: no edges in " + EDGES_RESOURCE);
        }
        List<RawEdge> raw = new ArrayList<>();
        for (Object edge : edgeList) {
            if (!(edge instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "concept graph snapshot: malformed edge entry in " + EDGES_RESOURCE);
            }
            String relation = stringOf(m.get("relation"));
            if (PART_OF.equals(relation)) {
                continue;   // curriculum structure — the runtime KG's authority, not this layer
            }
            raw.add(new RawEdge(
                    stringOf(m.get("source")),
                    relation,
                    stringOf(m.get("target")),
                    stringOf(m.get("validation_status"))));
        }
        return raw;
    }

    private static String stringOf(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "concept graph snapshot: missing/blank edge field in " + EDGES_RESOURCE);
        }
        return s;
    }

    private static byte[] readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("concept graph snapshot missing: " + path, e);
        }
    }

    static void requireSha256(String path, byte[] bytes, String expected) {
        String actual = sha256Hex(bytes);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException(
                    "concept graph snapshot drift: " + path + " sha256 " + actual
                            + " != pinned " + expected
                            + " — re-sync the settled bytes and update the pin deliberately");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
