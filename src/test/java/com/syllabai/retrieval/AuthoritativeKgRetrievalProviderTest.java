package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.KnowledgeRetriever;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KG adapter (T-C14): matched topics become candidates; prerequisites and
 * misconceptions are pedagogical context, NOT candidates (they are assembled
 * downstream and never mutated by provider output — educational-truth
 * invariant).
 */
class AuthoritativeKgRetrievalProviderTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());
    private static final UUID NODE_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000003e");
    private static final UUID PREREQ_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID MISCON_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000a0");

    private final KnowledgeRetriever delegate = mock(KnowledgeRetriever.class);
    private final AuthoritativeKgRetrievalProvider provider =
            new AuthoritativeKgRetrievalProvider(delegate);

    @Test
    @DisplayName("provider identity: authoritative-kg, available")
    void identity() {
        assertThat(provider.id()).isEqualTo("authoritative-kg");
        assertThat(provider.available()).isTrue();
    }

    @Test
    @DisplayName("blank query fails closed without calling the delegate")
    void blankQueryFailClosed() {
        assertThat(provider.retrieve(StructuredRetrievalQuery.of("", SCOPE, 5))).isEmpty();
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("null query is rejected")
    void nullQueryRejected() {
        assertThatThrownBy(() -> provider.retrieve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query");
    }

    @Test
    @DisplayName("matched topics become candidates; prerequisite/misconception context does not")
    void topicsOnlyBecomeCandidates() {
        KnowledgeRetriever.KnowledgeContext.MatchedTopic topic =
                new KnowledgeRetriever.KnowledgeContext.MatchedTopic(NODE_ID, "4CH1-1.26",
                        "Electrolysis", 0.9);
        KnowledgeRetriever.KnowledgeContext context = new KnowledgeRetriever.KnowledgeContext(
                List.of(topic),
                List.of(new KnowledgeRetriever.KnowledgeContext.PrerequisiteLink(
                        NODE_ID, PREREQ_ID, "Ionic bonding", 1)),
                List.of(new KnowledgeRetriever.KnowledgeContext.MisconceptionSignal(
                        NODE_ID, MISCON_ID, "electrons are used up")));
        when(delegate.retrieve("how does electrolysis work", 5, SCOPE)).thenReturn(context);

        List<RetrievalCandidate> candidates =
                provider.retrieve(StructuredRetrievalQuery.of("how does electrolysis work", SCOPE, 5));

        assertThat(candidates).hasSize(1);
        RetrievalCandidate c = candidates.get(0);
        assertThat(c.providerId()).isEqualTo("authoritative-kg");
        assertThat(c.evidenceLocator()).isEqualTo(NODE_ID.toString());
        assertThat(c.knowledgeNodeId()).isEqualTo(NODE_ID);
        assertThat(c.nodeCode()).isEqualTo("4CH1-1.26");
        assertThat(c.content()).isEqualTo("Electrolysis");
        assertThat(c.providerScore()).isEqualTo(0.9); // match specificity, native
        assertThat(c.documentRowId()).isNull();
        assertThat(c.validationStatus()).isNull();
    }

    @Test
    @DisplayName("empty intent match yields empty candidates — distinct from provider-down")
    void emptyMatchIsEmpty() {
        when(delegate.retrieve("gibberish", 5, SCOPE))
                .thenReturn(new KnowledgeRetriever.KnowledgeContext(List.of(), List.of(), List.of()));
        assertThat(provider.retrieve(StructuredRetrievalQuery.of("gibberish", SCOPE, 5)))
                .isEmpty();
    }
}
