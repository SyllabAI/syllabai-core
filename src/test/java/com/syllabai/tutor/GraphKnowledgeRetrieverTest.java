package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic intent matching (T-024): whole-token title matching, stop
 * tokens ignored, specificity ranking, no matches → empty context, and the
 * pedagogical context (prerequisites + misconceptions) follows the matched
 * topics.
 */
class GraphKnowledgeRetrieverTest {

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final GraphKnowledgeRetriever retriever = new GraphKnowledgeRetriever(graph);

    private final UUID molesId = UUID.randomUUID();
    private final UUID bondingId = UUID.randomUUID();
    private final UUID moleMisconceptionId = UUID.randomUUID();

    {
        when(graph.structureNodes()).thenReturn(List.of(
                node(molesId, "IALCHEM2018-U1-T1",
                        "Formulae, Equations and Amount of Substance"),
                node(bondingId, "IALCHEM2018-U1-T3", "Bonding and Structure"),
                node(UUID.randomUUID(), "IALCHEM2018-U2-T6", "Energetics")));
        when(graph.prerequisiteChain(bondingId)).thenReturn(List.of(
                new PrerequisiteView(molesId, "IALCHEM2018-U1-T1", "TOPIC",
                        "Formulae, Equations and Amount of Substance", 1)));
        when(graph.misconceptions(bondingId)).thenReturn(List.of(
                new NodeView(moleMisconceptionId, "MIS-T1.1-01", "MISCONCEPTION",
                        "Moles and grams are interchangeable", null, "UNVALIDATED", null,
                        List.of())));
    }

    @Test
    @DisplayName("query tokens match topic titles; two named tokens clear the precision floor")
    void matchesByTokenOverlap() {
        // "formulae" + "equations" of U1-T1 → specificity 2/4 = 0.5 (>= floor)
        KnowledgeContext context = retriever.retrieve(
                "how do I calculate moles, formulae and equations?", 5);

        assertThat(context.topics()).hasSize(1);
        assertThat(context.topics().get(0).code()).isEqualTo("IALCHEM2018-U1-T1");
        assertThat(context.topics().get(0).matchScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("precision floor: one generic token into a long spec title is NOT a match (Hamlet regression)")
    void weakSingleTokenMatchIsRejected() {
        // production shape: 4CH1-1.6C "understand how to plot and interpret
        // solubility curves" — "plot" alone scores 1/5 = 0.2 and previously
        // leaked an evidence item past the grounding gate (P6 battery). Below
        // the floor the context is empty, which feeds the deterministic
        // refusal path — never a fabricated answer.
        when(graph.structureNodes()).thenReturn(java.util.List.of(
                node(UUID.randomUUID(), "4CH1-1.6C",
                        "understand how to plot and interpret solubility curves")));

        KnowledgeContext context = retriever.retrieve("What is the plot of Shakespeare's Hamlet?", 5);

        assertThat(context.topics()).isEmpty();
        assertThat(context.prerequisites()).isEmpty();
        assertThat(context.misconceptions()).isEmpty();
    }

    @Test
    @DisplayName("plural normalization: 'bonding' queries match, stemmed both sides")
    void pluralNormalization() {
        KnowledgeContext context = retriever.retrieve("bonding", 5);
        assertThat(context.topics())
                .extracting(KnowledgeContext.MatchedTopic::code)
                .containsExactly("IALCHEM2018-U1-T3");
        // "bonding" names 1 of 2 title tokens ("structure" unnamed) → 0.5
        assertThat(context.topics().get(0).matchScore()).isEqualTo(0.5);

        KnowledgeContext full = retriever.retrieve("bonding and structure", 5);
        assertThat(full.topics().get(0).matchScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("stop tokens never match; unknown topics match nothing (no invention, §7)")
    void stopTokensAndUnknowns() {
        assertThat(retriever.retrieve("the and what how does", 5).topics()).isEmpty();
        assertThat(retriever.retrieve("photosynthesis in plants", 5).topics()).isEmpty();
        assertThat(retriever.retrieve("", 5).topics()).isEmpty();
    }

    @Test
    @DisplayName("prerequisites and misconceptions of matched topics are gathered")
    void gathersPedagogicalContext() {
        // "bonding" matches U1-T3 whose prerequisite chain (via mock) is the moles topic
        KnowledgeContext context = retriever.retrieve("bonding", 5);

        assertThat(context.prerequisites()).hasSize(1);
        assertThat(context.prerequisites().get(0).title())
                .isEqualTo("Formulae, Equations and Amount of Substance");
        // misconceptions (mocked) attach to the matched bonding topic
        assertThat(context.misconceptions()).hasSize(1);
        assertThat(context.misconceptions().get(0).title())
                .isEqualTo("Moles and grams are interchangeable");

        // unmatched topics contribute no pedagogical context
        KnowledgeContext unmatched = retriever.retrieve("photosynthesis", 5);
        assertThat(unmatched.prerequisites()).isEmpty();
        assertThat(unmatched.misconceptions()).isEmpty();
    }

    @Test
    @DisplayName("maxTopics bounds the match list")
    void boundsMatches() {
        assertThat(retriever.retrieve("formulae equations amount substance bonding", 1)
                .topics()).hasSize(1);
    }

    private KnowledgeNode node(UUID id, String code, String title) {
        KnowledgeNode n = new KnowledgeNode(code, NodeType.TOPIC, title, null,
                KnowledgeNode.ValidationStatus.VALIDATED, "test", "test");
        try {
            var field = KnowledgeNode.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(n, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return n;
    }
}
