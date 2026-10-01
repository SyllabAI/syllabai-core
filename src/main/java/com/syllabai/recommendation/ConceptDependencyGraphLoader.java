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
 * <p><strong>Snapshot contract (2026-09-25, close of T-C11 Batch 11; core-sync
 * GO 2026-10-01):</strong>
 * {@code classpath:concept-graph/concept_edges.yaml}, {@code concept-graph/concepts.yaml}
 * and {@code concept-graph/practicals.yaml} are byte-verbatim copies of the
 * settled store in the syllabai-resources repo (193 concept nodes + 12 practical
 * nodes / 488 edges / 277 semantic edges, of which 272 HUMAN_VALIDATED; the only
 * non-validated semantic edges are the 3 frozen pilot operator HOLDs and the 2
 * REVIEW_REQUIRED edges). All files are pinned by SHA-256 below: any drift — an
 * edited file, a re-synced store from a later batch — fails startup loudly
 * instead of silently changing recommendation inputs. Upgrading the snapshot
 * is therefore always a conscious, reviewable change: copy the new settled
 * bytes, update the three hashes, and update this comment. (The store files
 * are generated deterministically by the resources repo, so byte-identity is
 * meaningful.) Practical nodes participate because 19 validated
 * REQUIRES_PREREQUISITE edges name them — a real pedagogical dependency the
 * settled store carries (the practical requires its underlying concepts).
 * Since the 2026-10-01 practical-endpoint retarget those edges SOURCE at the
 * practicals' real spec statements (previously the ad-hoc 4CH1-PR-xx codes):
 * the loader therefore admits each practical's {@code spec_point} code as a
 * known endpoint — official store content, pinned via practicals.yaml; no
 * additional resource or pin is needed.</p>
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

    /** SHA-256 of the settled concept_edges.yaml snapshot (Batch-11 close, 2026-09-25). */
    static final String EDGES_SHA256 =
            "8a651dd9e60bfefa7a164102db23b10b700672daebc6449c126332180db24aad";
    /** SHA-256 of the settled concepts.yaml snapshot (Batch-11 close, 2026-09-25). */
    static final String NODES_SHA256 =
            "24fa91ac7149682b1083ff47112362a11180bcfb7899c1343c6f536682447e3f";
    /** SHA-256 of the settled practicals.yaml snapshot (c09 substrate, unchanged since Batch-4). */
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
        // the practicals' real spec statements are legal edge endpoints since the
        // 2026-10-01 endpoint retarget (the 19 validated practical prerequisite
        // edges source at them) — official store content, pinned via practicals.yaml
        nodeCodes.addAll(readPracticalSpecPointCodes(practicalsDoc));
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
    private static Set<String> readPracticalSpecPointCodes(Map<String, Object> practicalsDoc) {
        Object practicals = practicalsDoc == null ? null : practicalsDoc.get("practicals");
        if (!(practicals instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(
                    "concept graph snapshot: no practicals in " + PRACTICALS_RESOURCE);
        }
        Set<String> codes = new HashSet<>();
        for (Object practical : list) {
            if (!(practical instanceof Map<?, ?> m)
                    || !(m.get("spec_point") instanceof String sp) || sp.isBlank()) {
                throw new IllegalStateException("concept graph snapshot: practical without a "
                        + "spec_point in " + PRACTICALS_RESOURCE);
            }
            codes.add(sp);
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
