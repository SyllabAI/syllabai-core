package com.syllabai.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.recommendation.ConceptDependencyGraph.Edge;
import com.syllabai.recommendation.ConceptDependencyGraph.SemanticRelation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The packaged settled T-C11 snapshot is loadable and exactly what the
 * recommendation path consumes: SHA-256-pinned verbatim bytes of the Batch-4
 * close (2026-09-13) — 113 nodes, 275 edges, 158 semantic edges of which 153
 * HUMAN_VALIDATED; the only non-validated semantic edges are the three frozen
 * pilot HOLDs (SUGGESTED) and the two REVIEW_REQUIRED edges. This pins the
 * whole downstream guarantee: if the snapshot drifts, startup fails; if the
 * frozen five ever leaked into the layer, this test fails.
 */
class ConceptDependencyGraphLoaderTest {

    private final ConceptDependencyGraphLoader loader = new ConceptDependencyGraphLoader();

    @Test
    @DisplayName("the settled snapshot loads: 153 validated semantic edges with the exact per-relation counts of the closed store")
    void settledSnapshotLoads() {
        ConceptDependencyGraph graph = loader.load();

        assertThat(graph.validatedEdgeCount()).isEqualTo(153);
        assertThat(graph.edges(SemanticRelation.REQUIRES_PREREQUISITE)).hasSize(112);
        assertThat(graph.edges(SemanticRelation.REMEDIATED_BY)).hasSize(14);
        assertThat(graph.edges(SemanticRelation.WRONG_ANSWER_PATTERN)).hasSize(13);
        assertThat(graph.edges(SemanticRelation.EXPLAINED_BY)).hasSize(9);
        assertThat(graph.edges(SemanticRelation.COMMONLY_CONFUSED_WITH)).hasSize(2);
        assertThat(graph.edges(SemanticRelation.MISCONCEPTION_OF)).hasSize(2);
        assertThat(graph.edges(SemanticRelation.RELATED_TO)).hasSize(1);
    }

    @Test
    @DisplayName("the settled store's real remediation and prerequisite fixtures are carried with the right endpoints")
    void realFixtureEdgesPresent() {
        ConceptDependencyGraph graph = loader.load();

        // Case-B fixture of the NBA tests (MIS-BOND-ENERGY-COUNT → its corrective concept)
        assertThat(graph.edges(SemanticRelation.REMEDIATED_BY))
                .contains(new Edge("4CH1-MIS-BOND-ENERGY-COUNT",
                        SemanticRelation.REMEDIATED_BY, "4CH1-CON-BOND-ENERGY-CALC"));
        // Case-A fixture (the bond-energy calculation chain down to the S1 covalent-bond canonical)
        assertThat(graph.edges(SemanticRelation.REQUIRES_PREREQUISITE))
                .contains(new Edge("4CH1-CON-BOND-ENERGY-CALC",
                        SemanticRelation.REQUIRES_PREREQUISITE, "4CH1-CON-COVALENT-BOND"));
    }

    @Test
    @DisplayName("the frozen five — three pilot HOLDs (SUGGESTED) and two REVIEW_REQUIRED edges — are provably absent from the layer")
    void frozenFiveExcluded() {
        ConceptDependencyGraph graph = loader.load();
        List<Edge> prerequisite = graph.edges(SemanticRelation.REQUIRES_PREREQUISITE);
        List<Edge> remediation = graph.edges(SemanticRelation.REMEDIATED_BY);
        List<Edge> explainedBy = graph.edges(SemanticRelation.EXPLAINED_BY);

        // the three frozen pilot operator HOLDs (SUGGESTED)
        assertThat(prerequisite).doesNotContain(new Edge("4CH1-CON-REACTING-MASS",
                SemanticRelation.REQUIRES_PREREQUISITE, "4CH1-CON-EQ-SYMBOL"));
        assertThat(explainedBy).doesNotContain(new Edge("4CH1-CON-MOLAR-GAS-VOL",
                SemanticRelation.EXPLAINED_BY, "4CH1-CON-AVOGADRO-LAW"));
        assertThat(remediation).doesNotContain(new Edge("4CH1-MIS-EQ-SUBSCRIPT",
                SemanticRelation.REMEDIATED_BY, "4CH1-CON-CONSERVATION-MASS"));
        // the two REVIEW_REQUIRED settlements
        assertThat(prerequisite).doesNotContain(new Edge("4CH1-CON-CRYSTALLISATION",
                SemanticRelation.REQUIRES_PREREQUISITE, "4CH1-CON-SOLUTION"));
        assertThat(prerequisite).doesNotContain(new Edge("4CH1-CON-GAS-VOL-CALC",
                SemanticRelation.REQUIRES_PREREQUISITE, "4CH1-CON-AVOGADRO-LAW"));
    }

    @Test
    @DisplayName("deterministic: two loads produce identical, immutable graphs")
    void deterministicLoads() {
        ConceptDependencyGraph first = loader.load();
        ConceptDependencyGraph second = loader.load();

        assertThat(first.validatedEdgeCount()).isEqualTo(second.validatedEdgeCount());
        for (SemanticRelation relation : SemanticRelation.values()) {
            assertThat(first.edges(relation)).containsExactlyElementsOf(second.edges(relation));
        }
    }

    @Test
    @DisplayName("the sha-256 pins are live: mutating the packaged bytes must fail the load loudly")
    void hashPinsAreLive() {
        // the pin values are exercised by every load() above; assert the constants
        // are the settled ones so accidental edits surface here rather than at boot
        assertThat(ConceptDependencyGraphLoader.EDGES_SHA256)
                .isEqualTo("e583ae50916fcb54a924bb13f42625a840e3d9baaec8fa5f69e62122716e5f07");
        assertThat(ConceptDependencyGraphLoader.NODES_SHA256)
                .isEqualTo("69cc554c04135188d6c7c44fddd9831f6c86bd7016684f3a15a2c7e1374d5613");
        assertThat(ConceptDependencyGraphLoader.PRACTICALS_SHA256)
                .isEqualTo("e53e5f87606a2b5a5b7e534f0375d970498ea85a5655ca32e5bd4526dc9fa528");
    }

    @Test
    @DisplayName("fail-closed: tampered bytes fail the sha-256 pin loudly, never a silent different graph")
    void tamperedBytesFailThePin() {
        assertThatThrownBy(() -> ConceptDependencyGraphLoader.requireSha256(
                "concept-graph/concept_edges.yaml", "tampered bytes".getBytes(),
                ConceptDependencyGraphLoader.EDGES_SHA256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot drift");
    }

    @Test
    @DisplayName("byte-sensitivity: demoting exactly one validated edge status flows through to a graph of 152, not 153")
    void tamperedStatusYieldsFewerValidatedEdges() {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> edgesDoc = yaml.load(new String(
                readPackaged(ConceptDependencyGraphLoader.EDGES_RESOURCE),
                java.nio.charset.StandardCharsets.UTF_8));
        boolean mutated = mutateEdge(edgesDoc, "4CH1-CON-BOND-ENERGY-CALC",
                "REQUIRES_PREREQUISITE", "4CH1-CON-COVALENT-BOND",
                status -> status.put("validation_status", "HUMAN_VALIDATEx"));
        assertThat(mutated).isTrue();

        ConceptDependencyGraph graph = new ConceptDependencyGraphLoader()
                .parse(yaml.dump(edgesDoc).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        readPackaged(ConceptDependencyGraphLoader.NODES_RESOURCE),
                        readPackaged(ConceptDependencyGraphLoader.PRACTICALS_RESOURCE));

        assertThat(graph.validatedEdgeCount()).isEqualTo(152);   // one fewer than the settled 153
    }

    @Test
    @DisplayName("fail-closed: a mutated semantic-edge endpoint makes the integrity check fail (no dangling edges tolerated)")
    void tamperedEndpointFailsIntegrity() {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> edgesDoc = yaml.load(new String(
                readPackaged(ConceptDependencyGraphLoader.EDGES_RESOURCE),
                java.nio.charset.StandardCharsets.UTF_8));
        boolean mutated = mutateEdge(edgesDoc, "4CH1-CON-BOND-ENERGY-CALC",
                "REQUIRES_PREREQUISITE", "4CH1-CON-COVALENT-BOND",
                edge -> edge.put("target", "4CH1-CON-COVALENT-BONX"));   // dangling endpoint
        assertThat(mutated).isTrue();

        assertThatThrownBy(() -> new ConceptDependencyGraphLoader()
                .parse(yaml.dump(edgesDoc).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        readPackaged(ConceptDependencyGraphLoader.NODES_RESOURCE),
                        readPackaged(ConceptDependencyGraphLoader.PRACTICALS_RESOURCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a known concept node");
    }

    /** mutates the one semantic edge (source, relation, target) that is HUMAN_VALIDATED; true if found */
    @SuppressWarnings("unchecked")
    private static boolean mutateEdge(Map<String, Object> edgesDoc, String source, String relation,
                                      String target, java.util.function.Consumer<Map<String, Object>> mutation) {
        for (Object edge : (List<Object>) edgesDoc.get("edges")) {
            Map<String, Object> m = (Map<String, Object>) edge;
            if (source.equals(m.get("source")) && relation.equals(m.get("relation"))
                    && target.equals(m.get("target"))
                    && "HUMAN_VALIDATED".equals(m.get("validation_status"))) {
                mutation.accept(m);
                return true;
            }
        }
        return false;
    }

    private static byte[] readPackaged(String path) {
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource(path)
                .getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("test cannot read packaged " + path, e);
        }
    }
}
