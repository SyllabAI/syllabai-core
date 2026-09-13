package com.syllabai.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.recommendation.ConceptDependencyGraph.Edge;
import com.syllabai.recommendation.ConceptDependencyGraph.RawEdge;
import com.syllabai.recommendation.ConceptDependencyGraph.SemanticRelation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The T-C11 concept dependency layer's own contract (v1.1 integration, T-C11
 * authoritative-evidence rule): the factory is the single filtering point —
 * only HUMAN_VALIDATED edges can enter (held SUGGESTED / REVIEW_REQUIRED are
 * excluded by construction), every relation and endpoint must be known, and
 * duplicates fail loudly. A graph that cannot be constructed correctly cannot
 * be consumed by the recommendation path at all.
 */
class ConceptDependencyGraphTest {

    private static final Set<String> CODES = Set.of("CON-A", "CON-B", "MIS-X", "CON-C");

    private static RawEdge edge(String source, String relation, String target, String status) {
        return new RawEdge(source, relation, target, status);
    }

    @Test
    @DisplayName("only HUMAN_VALIDATED edges enter; SUGGESTED (held) and REVIEW_REQUIRED are excluded by construction")
    void filtersToHumanValidatedOnly() {
        ConceptDependencyGraph graph = ConceptDependencyGraph.of(List.of(
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-B", "HUMAN_VALIDATED"),
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-C", "SUGGESTED"),          // frozen pilot HOLD
                edge("MIS-X", "REMEDIATED_BY", "CON-A", "REVIEW_REQUIRED"),            // frozen RR
                edge("MIS-X", "REMEDIATED_BY", "CON-B", "UNVALIDATED"),
                edge("CON-B", "EXPLAINED_BY", "CON-A", "HUMAN_VALIDATED")),
                CODES);

        assertThat(graph.validatedEdgeCount()).isEqualTo(2);
        assertThat(graph.edges(SemanticRelation.REQUIRES_PREREQUISITE))
                .containsExactly(new Edge("CON-A", SemanticRelation.REQUIRES_PREREQUISITE, "CON-B"));
        assertThat(graph.edges(SemanticRelation.REMEDIATED_BY)).isEmpty();
        assertThat(graph.edges(SemanticRelation.EXPLAINED_BY))
                .containsExactly(new Edge("CON-B", SemanticRelation.EXPLAINED_BY, "CON-A"));
        assertThat(graph.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("empty raw input yields the honest empty graph, and empty() is empty")
    void emptyGraph() {
        assertThat(ConceptDependencyGraph.of(List.of(), CODES).isEmpty()).isTrue();
        assertThat(ConceptDependencyGraph.of(List.of(), CODES).validatedEdgeCount()).isZero();
        assertThat(ConceptDependencyGraph.empty().isEmpty()).isTrue();
        assertThat(ConceptDependencyGraph.empty().edges(SemanticRelation.REQUIRES_PREREQUISITE))
                .isEmpty();
    }

    @Test
    @DisplayName("PART_OF is deliberately absent from the layer's vocabulary — curriculum structure stays the runtime KG's authority")
    void partOfIsNotAVocabularyMember() {
        // the enum mirrors the settled store's SEMANTIC kinds only; PART_OF is
        // dropped by the loader and unknown to the layer by design
        assertThat(java.util.Arrays.stream(SemanticRelation.values())
                .noneMatch(r -> r.name().equals("PART_OF"))).isTrue();
    }

    @Test
    @DisplayName("fail-closed: an unknown relation on a validated edge is a defect, not something to tolerate")
    void unknownRelationFails() {
        assertThatThrownBy(() -> ConceptDependencyGraph.of(List.of(
                edge("CON-A", "NOT_A_RELATION", "CON-B", "HUMAN_VALIDATED")), CODES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_A_RELATION");
    }

    @Test
    @DisplayName("fail-closed: a dangling endpoint (edge to a code not in the node set) fails")
    void danglingEndpointFails() {
        assertThatThrownBy(() -> ConceptDependencyGraph.of(List.of(
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-MISSING", "HUMAN_VALIDATED")), CODES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CON-MISSING");
    }

    @Test
    @DisplayName("fail-closed: a duplicate validated edge fails (the settled store has none)")
    void duplicateEdgeFails() {
        assertThatThrownBy(() -> ConceptDependencyGraph.of(List.of(
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-B", "HUMAN_VALIDATED"),
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-B", "HUMAN_VALIDATED")), CODES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("deterministic ordering: edges are returned sorted by (source, target) regardless of input order")
    void deterministicOrdering() {
        ConceptDependencyGraph graph = ConceptDependencyGraph.of(List.of(
                edge("CON-B", "REQUIRES_PREREQUISITE", "CON-C", "HUMAN_VALIDATED"),
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-C", "HUMAN_VALIDATED"),
                edge("CON-A", "REQUIRES_PREREQUISITE", "CON-B", "HUMAN_VALIDATED")), CODES);

        assertThat(graph.edges(SemanticRelation.REQUIRES_PREREQUISITE).stream().map(Edge::source))
                .containsExactly("CON-A", "CON-A", "CON-B");
    }
}
