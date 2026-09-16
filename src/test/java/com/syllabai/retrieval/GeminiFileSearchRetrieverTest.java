package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C15 stub: the File Search provider is honestly UNAVAILABLE until T-C15 —
 * {@code available() == false} (provider-down, distinct from no-results) and an
 * empty candidate list. Google types never cross the fabric port (the stub has
 * none; T-C15 keeps it that way).
 */
class GeminiFileSearchRetrieverTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.fromString("00000000-0000-0000-0000-0000000004c1"), "4CH1-IT", Set.of());

    private final GeminiFileSearchRetriever retriever = new GeminiFileSearchRetriever();

    @Test
    @DisplayName("honest UNAVAILABLE: id gemini-file-search, available() == false, retrieve() empty")
    void unavailablePosture() {
        assertThat(retriever.id()).isEqualTo("gemini-file-search");
        assertThat(retriever.available()).isFalse();
        assertThat(retriever.retrieve(StructuredRetrievalQuery.of("anything", SCOPE, 5)))
                .isEmpty();
    }

    @Test
    @DisplayName("null query is rejected even when unavailable")
    void nullQueryRejected() {
        assertThatThrownBy(() -> retriever.retrieve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query");
    }
}
