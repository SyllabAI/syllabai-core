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
 *
 * <p>T-C20: the SERVING-ELIGIBLE surface ({@code searchServingEligible} — the
 * method {@code ContentRetrievalService} drives) additionally carries the
 * VALIDATED-only gate on BOTH branches (owning paper for the exam-paper
 * branch, the chunk's own document for the knowledge-layer subject branch);
 * the neutral {@code search} stays ungated as the benchmark/audit surface.
 * Row-level gate behavior is proven by ContentPipelineIT's T-C20 controls.</p>
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
                .contains("p.mark_scheme_document_id = d.document_id")
                // V33: embed-revision read filter + subject branch for paper-less chunks
                .contains("c.embed_rev = ?")
                .contains("from subjects s2")
                .contains("s2.id = c.subject_id");
    }

    @Test
    @DisplayName("V33: bind order is vector, embed_rev, cv id (paper branch), cv id (subject branch), vector, limit")
    void bindOrderCarriesEmbedRevAndScope() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search(new float[] {0.1f}, null, CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("c.embed_rev = ?");
        Object[] bound = args.getValue();
        assertThat(bound).hasSize(6);
        assertThat(bound[0]).isEqualTo("[0.1]");
        assertThat(bound[1]).isEqualTo(ChunkVectorRepository.CURRENT_EMBED_REV);
        assertThat(bound[2]).isEqualTo(CV_ID);
        assertThat(bound[3]).isEqualTo(CV_ID);
        assertThat(bound[5]).isEqualTo(5);
    }

    // ── T-C20: the serving-eligible surface (ContentRetrievalService's path) ──

    @Test
    @DisplayName("T-C20: serving-eligible search rejects a null curriculum id before any SQL runs")
    void servingEligibleNullScopeRejected() {
        assertThatThrownBy(() -> repository.searchServingEligible(new float[] {0.1f}, null, null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
    }

    @Test
    @DisplayName("T-C20: serving-eligible SQL carries the VALIDATED gate on BOTH branches")
    void servingEligibleSqlCarriesValidatedGate() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchServingEligible(new float[] {0.1f}, Document.Kind.MARK_SCHEME, CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        String value = sql.getValue();
        // paper branch: the owning paper must be VALIDATED (lexical mirror)
        assertThat(value)
                .contains("p.validation_state = 'VALIDATED'")
                // subject branch: the knowledge-layer document itself must be VALIDATED
                .contains("d.validation_state = 'VALIDATED'")
                .contains("s2.id = c.subject_id");
        // the neutral benchmark surface stays ungated — both surfaces exist side by side
        assertThat(countOccurrences(value, "validation_state = 'VALIDATED'")).isEqualTo(2);
    }

    @Test
    @DisplayName("T-C20: serving-eligible bind order matches the neutral surface (6 binds)")
    void servingEligibleBindOrder() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchServingEligible(new float[] {0.1f}, null, CV_ID, 5);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), args.capture());
        Object[] bound = args.getValue();
        assertThat(bound).hasSize(6);
        assertThat(bound[0]).isEqualTo("[0.1]");
        assertThat(bound[1]).isEqualTo(ChunkVectorRepository.CURRENT_EMBED_REV);
        assertThat(bound[2]).isEqualTo(CV_ID);
        assertThat(bound[3]).isEqualTo(CV_ID);
        assertThat(bound[5]).isEqualTo(5);
    }

    /** Occurrence count of a literal substring (the VALIDATED gate appears once per branch). */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
