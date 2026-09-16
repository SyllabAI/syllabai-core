package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * T-C07: the vector search SQL carries the MANDATORY curriculum predicate as a
 * bound parameter — a null curriculum version id is rejected before any SQL
 * runs (retrieval never serves unscoped). The predicate's real row-level
 * behavior (foreign-curriculum chunks excluded, unlinked documents invisible)
 * is proven against real Postgres by ContentPipelineIT's negative controls on
 * the CI lane.
 */
class ChunkVectorRepositoryTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChunkVectorRepository repository = new ChunkVectorRepository(jdbc);

    @Test
    @DisplayName("null curriculum version id is rejected before any SQL runs (T-C07)")
    void nullScopeRejected() {
        assertThatThrownBy(() -> repository.search(new float[] {0.1f}, null, null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
    }

    @Test
    @DisplayName("search SQL carries the EXISTS curriculum predicate with the cv id bound")
    void sqlCarriesMandatoryPredicate() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search(new float[] {0.1f}, Document.Kind.MARK_SCHEME, CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("exists")
                .contains("exam_papers p")
                .contains("join subjects s on s.id = p.subject_id")
                .contains("s.curriculum_version_id = ?")
                .contains("p.question_paper_document_id = d.document_id")
                .contains("p.mark_scheme_document_id = d.document_id");
    }
}
