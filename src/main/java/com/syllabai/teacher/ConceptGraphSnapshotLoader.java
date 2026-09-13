package com.syllabai.teacher;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the pinned 4CH1 curriculum + T-C11 concept-graph snapshots for the
 * teacher-side seed (V15). Six byte-verbatim classpath copies of the
 * syllabai-resources store, each pinned by SHA-256 — any drift fails the seed
 * loudly instead of silently re-seeding changed data.
 *
 * <p>Files: {@code concept-graph/specification_points.yaml} (182 official spec
 * points, verbatim wording), {@code topics.yaml} (4 sections + 28 subsections),
 * {@code relationships.yaml} (210 PART_OF structure edges, RULE_DERIVED,
 * operator-PR-reviewed) — the official curriculum anchor — plus the three
 * session-55 files ({@code concepts.yaml} 113 concept/misconception nodes,
 * {@code concept_edges.yaml} 275 edges of which 153 HUMAN_VALIDATED,
 * {@code practicals.yaml} 12 required practicals) — the settled T-C11 graph
 * layer.</p>
 *
 * <p>This loader deliberately keeps the store's own epistemic statuses intact
 * (§8A.4: human validation does not erase provenance): the snapshot exposes
 * the 117 SUGGESTED anchor PART_OF edges (concept→SpecificationPoint) and the
 * 153 HUMAN_VALIDATED semantic edges separately, and drops nothing silently —
 * the 5 frozen non-validated semantic edges (3 pilot HOLDs + 2
 * REVIEW_REQUIRED) are excluded <em>by count check</em> so the seed provably
 * never materializes them.</p>
 */
@Component
public class ConceptGraphSnapshotLoader {

    static final String SPEC_POINTS_RESOURCE = "concept-graph/specification_points.yaml";
    static final String TOPICS_RESOURCE = "concept-graph/topics.yaml";
    static final String RELATIONSHIPS_RESOURCE = "concept-graph/relationships.yaml";
    static final String CONCEPTS_RESOURCE = "concept-graph/concepts.yaml";
    static final String CONCEPT_EDGES_RESOURCE = "concept-graph/concept_edges.yaml";
    static final String PRACTICALS_RESOURCE = "concept-graph/practicals.yaml";

    // session-55 pins (the settled T-C11 store, Batch-4 close 2026-09-13)
    static final String CONCEPTS_SHA256 =
            "69cc554c04135188d6c7c44fddd9831f6c86bd7016684f3a15a2c7e1374d5613";
    static final String CONCEPT_EDGES_SHA256 =
            "e583ae50916fcb54a924bb13f42625a840e3d9baaec8fa5f69e62122716e5f07";
    static final String PRACTICALS_SHA256 =
            "e53e5f87606a2b5a5b7e534f0375d970498ea85a5655ca32e5bd4526dc9fa528";

    // V15 pins (the c09 phase-1 curriculum substrate, same store commit)
    static final String SPEC_POINTS_SHA256 =
            "b1fea69953205077e2fac94aee00b2b535234936f432b4e256bd6bd90715af0a";
    static final String TOPICS_SHA256 =
            "a7b14cbdd34bb9fee8ce0e1155da784414b2d2fd57e942d0f2e932d259be9782";
    static final String RELATIONSHIPS_SHA256 =
            "b3b7529222d77d800cb404724c80c9dffb3cdd584103edafd7c76b70a58a1249";

    /** Store facts the loader fail-closes on (V15 snapshot contract). */
    static final int SECTION_COUNT = 4;
    static final int SUBSECTION_COUNT = 28;
    static final int SPEC_POINT_COUNT = 182;
    static final int PRACTICAL_COUNT = 12;
    static final int CONCEPT_NODE_COUNT = 113;
    static final int ANCHOR_EDGE_COUNT = 117;
    static final int VALIDATED_SEMANTIC_EDGE_COUNT = 153;
    /** 3 frozen pilot HOLDs + 2 REVIEW_REQUIRED — must never reach the KG. */
    static final int EXCLUDED_SEMANTIC_EDGE_COUNT = 5;

    /** Loads and verifies all six pinned snapshots; fails fast on any drift. */
    public ConceptGraphSnapshot load() {
        byte[] specPoints = readResource(SPEC_POINTS_RESOURCE);
        byte[] topics = readResource(TOPICS_RESOURCE);
        byte[] relationships = readResource(RELATIONSHIPS_RESOURCE);
        byte[] concepts = readResource(CONCEPTS_RESOURCE);
        byte[] conceptEdges = readResource(CONCEPT_EDGES_RESOURCE);
        byte[] practicals = readResource(PRACTICALS_RESOURCE);
        requireSha256(SPEC_POINTS_RESOURCE, specPoints, SPEC_POINTS_SHA256);
        requireSha256(TOPICS_RESOURCE, topics, TOPICS_SHA256);
        requireSha256(RELATIONSHIPS_RESOURCE, relationships, RELATIONSHIPS_SHA256);
        requireSha256(CONCEPTS_RESOURCE, concepts, CONCEPTS_SHA256);
        requireSha256(CONCEPT_EDGES_RESOURCE, conceptEdges, CONCEPT_EDGES_SHA256);
        requireSha256(PRACTICALS_RESOURCE, practicals, PRACTICALS_SHA256);
        return parse(specPoints, topics, relationships, concepts, conceptEdges, practicals);
    }

    // ── snapshot model ─────────────────────────────────────────────

    /**
     * The pinned 4CH1 substrate, deterministic orderings throughout:
     * sections/subsections/SPs by store ordering, nodes and edges by code.
     */
    public record ConceptGraphSnapshot(
            List<Section> sections,
            List<Subsection> subsections,
            List<SpecPoint> specPoints,
            List<Practical> practicals,
            List<ConceptNodeRecord> conceptNodes,
            List<AnchorEdge> anchorEdges,
            List<ValidatedEdge> validatedSemanticEdges) {

        public record Section(String code, String title, int ordering) {
        }

        public record Subsection(String code, String title, String sectionCode, int ordering) {
        }

        /** One official specification point — the authoritative curriculum anchor. */
        public record SpecPoint(String code, String officialCode, String wording,
                                String sectionCode, String subsectionCode,
                                int ordering, int globalOrder, boolean cPoint, boolean practical) {
        }

        /** One required practical (official spec content, anchored on its SP). */
        public record Practical(String code, String specPointCode, String summary, int ordering) {
        }

        /** One settled-store concept/misconception node (identity, family, aliases). */
        public record ConceptNodeRecord(String code, String family, String title,
                                        List<String> aliases) {
        }

        /** A SUGGESTED concept→SP anchor PART_OF edge (store status preserved). */
        public record AnchorEdge(String conceptCode, String specPointCode, String role) {
        }

        /**
         * One HUMAN_VALIDATED semantic edge, carrying its store provenance
         * (extraction pass, derivation method, operator validation).
         */
        public record ValidatedEdge(String source, String target, String relation,
                                    Double confidence, String rationale, String provenance) {
        }
    }

    // ── parsing (SafeConstructor: static packaged resources, never user input) ──

    ConceptGraphSnapshot parse(byte[] specPointsBytes, byte[] topicsBytes,
                               byte[] relationshipsBytes, byte[] conceptsBytes,
                               byte[] conceptEdgesBytes, byte[] practicalsBytes) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        Map<String, Object> specDoc = loadDoc(yaml, SPEC_POINTS_RESOURCE, specPointsBytes);
        Map<String, Object> topicsDoc = loadDoc(yaml, TOPICS_RESOURCE, topicsBytes);
        Map<String, Object> relDoc = loadDoc(yaml, RELATIONSHIPS_RESOURCE, relationshipsBytes);
        Map<String, Object> conceptsDoc = loadDoc(yaml, CONCEPTS_RESOURCE, conceptsBytes);
        Map<String, Object> edgesDoc = loadDoc(yaml, CONCEPT_EDGES_RESOURCE, conceptEdgesBytes);
        Map<String, Object> practicalsDoc = loadDoc(yaml, PRACTICALS_RESOURCE, practicalsBytes);

        List<ConceptGraphSnapshot.Section> sections = parseSections(topicsDoc);
        List<ConceptGraphSnapshot.Subsection> subsections = parseSubsections(topicsDoc);
        List<ConceptGraphSnapshot.SpecPoint> specPoints = parseSpecPoints(specDoc);
        List<ConceptGraphSnapshot.Practical> practicals = parsePracticals(practicalsDoc);
        List<ConceptGraphSnapshot.ConceptNodeRecord> nodes = parseConceptNodes(conceptsDoc);

        require(sections.size() == SECTION_COUNT, "section count");
        require(subsections.size() == SUBSECTION_COUNT, "subsection count");
        require(specPoints.size() == SPEC_POINT_COUNT, "spec point count");
        require(practicals.size() == PRACTICAL_COUNT, "practical count");
        require(nodes.size() == CONCEPT_NODE_COUNT, "concept node count");

        // referential integrity of the curriculum substrate
        Set<String> sectionCodes = codeSet(sections, ConceptGraphSnapshot.Section::code);
        Set<String> subsectionCodes = codeSet(subsections, ConceptGraphSnapshot.Subsection::code);
        Set<String> spCodes = codeSet(specPoints, ConceptGraphSnapshot.SpecPoint::code);
        for (ConceptGraphSnapshot.Subsection s : subsections) {
            require(sectionCodes.contains(s.sectionCode()),
                    "subsection " + s.code() + " references unknown section " + s.sectionCode());
        }
        for (ConceptGraphSnapshot.SpecPoint sp : specPoints) {
            require(sectionCodes.contains(sp.sectionCode()) && subsectionCodes.contains(sp.subsectionCode()),
                    "spec point " + sp.code() + " references unknown section/subsection");
        }
        for (ConceptGraphSnapshot.Practical p : practicals) {
            require(spCodes.contains(p.specPointCode()),
                    "practical " + p.code() + " references unknown spec point " + p.specPointCode());
        }
        requireStructureEdges(relDoc, spCodes, subsectionCodes);

        // the concept graph layer: nodes + anchors + validated semantic edges
        Set<String> nodeCodes = new HashSet<>();
        for (ConceptGraphSnapshot.ConceptNodeRecord n : nodes) {
            require(nodeCodes.add(n.code()), "duplicate concept node code " + n.code());
        }
        nodeCodes.addAll(codeSet(practicals, ConceptGraphSnapshot.Practical::code));
        List<ConceptGraphSnapshot.AnchorEdge> anchors = new ArrayList<>();
        List<ConceptGraphSnapshot.ValidatedEdge> validated = new ArrayList<>();
        int excluded = 0;
        int seenPartOf = 0;
        for (Object edge : list(edgesDoc.get("edges"), "edges", CONCEPT_EDGES_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) edge;
            String relation = string(m.get("relation"));
            String status = string(m.get("validation_status"));
            if ("PART_OF".equals(relation)) {
                seenPartOf++;
                anchors.add(new ConceptGraphSnapshot.AnchorEdge(
                        string(m.get("source")), string(m.get("target")),
                        string(m.get("role"))));
                continue;
            }
            if ("HUMAN_VALIDATED".equals(status)) {
                validated.add(parseValidatedEdge(m));
            } else {
                excluded++;   // 3 pilot HOLDs + 2 REVIEW_REQUIRED — never materialized
            }
        }
        require(seenPartOf == ANCHOR_EDGE_COUNT, "anchor PART_OF edge count");
        require(validated.size() == VALIDATED_SEMANTIC_EDGE_COUNT, "validated semantic edge count");
        require(excluded == EXCLUDED_SEMANTIC_EDGE_COUNT, "excluded semantic edge count");

        anchors.sort(Comparator.comparing(ConceptGraphSnapshot.AnchorEdge::conceptCode)
                .thenComparing(ConceptGraphSnapshot.AnchorEdge::specPointCode));
        validated.sort(Comparator.comparing(ConceptGraphSnapshot.ValidatedEdge::relation)
                .thenComparing(ConceptGraphSnapshot.ValidatedEdge::source)
                .thenComparing(ConceptGraphSnapshot.ValidatedEdge::target));

        for (ConceptGraphSnapshot.AnchorEdge a : anchors) {
            require(nodeCodes.contains(a.conceptCode()) && spCodes.contains(a.specPointCode()),
                    "anchor edge " + a.conceptCode() + " -> " + a.specPointCode()
                            + " has an unknown endpoint");
        }
        for (ConceptGraphSnapshot.ValidatedEdge e : validated) {
            require(nodeCodes.contains(e.source()) && nodeCodes.contains(e.target()),
                    "semantic edge " + e.source() + " -> " + e.target()
                            + " has an unknown endpoint");
        }

        return new ConceptGraphSnapshot(sections, subsections, specPoints, practicals,
                nodes, List.copyOf(anchors), List.copyOf(validated));
    }

    private static ConceptGraphSnapshot.ValidatedEdge parseValidatedEdge(Map<?, ?> m) {
        Map<?, ?> provenance = m.get("provenance") instanceof Map<?, ?> p ? p : Map.of();
        String pass = str(provenance.get("extraction_pass"));
        String method = str(provenance.get("derivation_method"));
        String validatedBy = str(m.get("validated_by"));
        String validatedDate = str(m.get("validated_date"));
        String provenanceLine = bound("t-c11:settled"
                + (pass.isBlank() ? "" : "|pass:" + pass)
                + (method.isBlank() ? "" : "|method:" + method)
                + (validatedBy.isBlank() ? "" : "|validated_by:" + validatedBy)
                + (validatedDate.isBlank() ? "" : "|date:" + validatedDate), 300);
        Double confidence = m.get("confidence") instanceof Number n ? n.doubleValue() : null;
        String rationale = bound(str(provenance.get("derivation_notes")), 500);
        return new ConceptGraphSnapshot.ValidatedEdge(
                string(m.get("source")), string(m.get("target")), string(m.get("relation")),
                confidence, rationale, provenanceLine);
    }

    private static List<ConceptGraphSnapshot.Section> parseSections(Map<String, Object> topicsDoc) {
        List<ConceptGraphSnapshot.Section> out = new ArrayList<>();
        for (Object t : list(topicsDoc.get("topics"), "topics", TOPICS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            out.add(new ConceptGraphSnapshot.Section(
                    string(m.get("code")), string(m.get("title")), intOf(m.get("ordering"))));
        }
        out.sort(Comparator.comparingInt(ConceptGraphSnapshot.Section::ordering));
        return out;
    }

    private static List<ConceptGraphSnapshot.Subsection> parseSubsections(Map<String, Object> topicsDoc) {
        List<ConceptGraphSnapshot.Subsection> out = new ArrayList<>();
        for (Object t : list(topicsDoc.get("subtopics"), "subtopics", TOPICS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            String code = string(m.get("code"));
            out.add(new ConceptGraphSnapshot.Subsection(
                    code, string(m.get("title")), sectionOfSubsection(code),
                    intOf(m.get("ordering"))));
        }
        out.sort(Comparator.comparing(ConceptGraphSnapshot.Subsection::sectionCode)
                .thenComparingInt(ConceptGraphSnapshot.Subsection::ordering));
        return out;
    }

    /** 4CH1-S1-a → 4CH1-S1 (store subsection codes embed their section). */
    private static String sectionOfSubsection(String subsectionCode) {
        int i = subsectionCode.lastIndexOf('-');
        require(i > 0, "subsection code shape: " + subsectionCode);
        return subsectionCode.substring(0, i);
    }

    private static List<ConceptGraphSnapshot.SpecPoint> parseSpecPoints(Map<String, Object> specDoc) {
        List<ConceptGraphSnapshot.SpecPoint> out = new ArrayList<>();
        for (Object t : list(specDoc.get("specification_points"), "specification_points",
                SPEC_POINTS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            out.add(new ConceptGraphSnapshot.SpecPoint(
                    string(m.get("code")), string(m.get("official_code")),
                    string(m.get("official_wording")), string(m.get("section")),
                    string(m.get("subsection")), intOf(m.get("ordering")),
                    intOf(m.get("global_order")), Boolean.TRUE.equals(m.get("c_point")),
                    Boolean.TRUE.equals(m.get("practical"))));
        }
        out.sort(Comparator.comparingInt(ConceptGraphSnapshot.SpecPoint::globalOrder));
        return out;
    }

    private static List<ConceptGraphSnapshot.Practical> parsePracticals(Map<String, Object> practicalsDoc) {
        List<ConceptGraphSnapshot.Practical> out = new ArrayList<>();
        for (Object t : list(practicalsDoc.get("practicals"), "practicals", PRACTICALS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            out.add(new ConceptGraphSnapshot.Practical(
                    string(m.get("code")), string(m.get("spec_point")),
                    string(m.get("summary")), intOf(m.get("ordering"))));
        }
        out.sort(Comparator.comparingInt(ConceptGraphSnapshot.Practical::ordering));
        return out;
    }

    private static List<ConceptGraphSnapshot.ConceptNodeRecord> parseConceptNodes(
            Map<String, Object> conceptsDoc) {
        List<ConceptGraphSnapshot.ConceptNodeRecord> out = new ArrayList<>();
        for (Object t : list(conceptsDoc.get("nodes"), "nodes", CONCEPTS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            String family = string(m.get("family"));
            require("CONCEPT".equals(family) || "MISCONCEPTION".equals(family),
                    "concept node family " + family);
            List<String> aliases = new ArrayList<>();
            if (m.get("aliases") instanceof List<?> list) {
                for (Object a : list) {
                    if (a instanceof String s && !s.isBlank()) {
                        aliases.add(s);
                    }
                }
            }
            out.add(new ConceptGraphSnapshot.ConceptNodeRecord(
                    string(m.get("code")), family, string(m.get("title")), List.copyOf(aliases)));
        }
        out.sort(Comparator.comparing(ConceptGraphSnapshot.ConceptNodeRecord::code));
        return out;
    }

    /** The 210 RULE_DERIVED structure edges (182 SP→subsection + 28 subsection→section). */
    private static void requireStructureEdges(Map<String, Object> relDoc, Set<String> spCodes,
                                               Set<String> subsectionCodes) {
        int spToSubsection = 0;
        int subsectionToSection = 0;
        for (Object t : list(relDoc.get("edges"), "edges", RELATIONSHIPS_RESOURCE)) {
            Map<?, ?> m = (Map<?, ?>) t;
            require("PART_OF".equals(string(m.get("relation"))), "structure edge relation");
            String from = string(m.get("from"));
            String to = string(m.get("to"));
            if (spCodes.contains(from)) {
                spToSubsection++;
                require(subsectionCodes.contains(to), "SP " + from + " -> unknown " + to);
            } else {
                subsectionToSection++;
                require(subsectionCodes.contains(from), "structure from " + from);
            }
        }
        require(spToSubsection == SPEC_POINT_COUNT, "SP→subsection edge count");
        require(subsectionToSection == SUBSECTION_COUNT, "subsection→section edge count");
    }

    // ── helpers ────────────────────────────────────────────────────

    private static Map<String, Object> loadDoc(Yaml yaml, String resource, byte[] bytes) {
        Object doc = yaml.load(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        require(doc instanceof Map<?, ?>, resource + " is not a YAML mapping");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) doc;
        return map;
    }

    private static List<?> list(Object value, String key, String resource) {
        require(value instanceof List<?> list && !list.isEmpty(),
                resource + ": missing/empty '" + key + "' list");
        return (List<?>) value;
    }

    private static String string(Object value) {
        require(value instanceof String s && !s.isBlank(), "missing/blank string field");
        return (String) value;
    }

    private static String str(Object value) {
        return value instanceof String s ? s : "";
    }

    private static int intOf(Object value) {
        if (!(value instanceof Number n)) {
            throw new IllegalStateException(
                    "concept graph snapshot: missing/invalid integer field");
        }
        return n.intValue();
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("concept graph snapshot: " + what);
        }
    }

    private static <T> Set<String> codeSet(List<T> records, java.util.function.Function<T, String> code) {
        Set<String> codes = new HashSet<>();
        for (T r : records) {
            require(codes.add(code.apply(r)), "duplicate code " + code.apply(r));
        }
        return Set.copyOf(codes);
    }

    private static String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
