package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fabric query contract: the curriculum scope is mandatory (T-C07
 * fail-closed, un-bypassable by construction), nullable collection components
 * normalize to empty (never null), and the limit is validated.
 */
class StructuredRetrievalQueryTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());

    @Test
    @DisplayName("null curriculum scope is rejected — retrieval never runs unscoped (T-C07)")
    void nullScopeRejected() {
        assertThatThrownBy(() -> new StructuredRetrievalQuery("q", null, null, null, null, null, null, null, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("never runs unscoped");
    }

    @Test
    @DisplayName("null query text is rejected")
    void nullQueryRejected() {
        assertThatThrownBy(() -> new StructuredRetrievalQuery(null, SCOPE, null, null, null, null, null, null, 5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("normalizedQuery");
    }

    @Test
    @DisplayName("negative limit is rejected")
    void negativeLimitRejected() {
        assertThatThrownBy(() -> new StructuredRetrievalQuery("q", SCOPE, null, null, null, null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("nullable components normalize to empty; curriculumVersionId derives from the scope")
    void nullablesNormalize() {
        StructuredRetrievalQuery query =
                new StructuredRetrievalQuery("q", SCOPE, null, null, null, null, null, null, 5);
        assertThat(query.specificationPointIds()).isEmpty();
        assertThat(query.conceptIds()).isEmpty();
        assertThat(query.misconceptionIds()).isEmpty();
        assertThat(query.resourceKinds()).isEmpty();
        assertThat(query.learnerSignals()).isEqualTo(LearnerSignals.empty());
        assertThat(query.evidence()).isEqualTo(EvidenceRequirements.defaults());
        assertThat(query.curriculumVersionId()).isEqualTo(CV_ID);
    }

    @Test
    @DisplayName("provided collections are defensively copied; the minimal factory works")
    void defensiveCopiesAndFactory() {
        StructuredRetrievalQuery query = StructuredRetrievalQuery.of("moles", SCOPE, 10);
        assertThat(query.normalizedQuery()).isEqualTo("moles");
        assertThat(query.limit()).isEqualTo(10);
        Set<String> specs = query.specificationPointIds();
        assertThatThrownBy(() -> specs.add("4CH1-1.1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
