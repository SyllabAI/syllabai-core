package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * T-C14: the lexical search SQL carries the MANDATORY curriculum predicate as a
 * bound parameter — a null curriculum version id is rejected before any SQL
 * runs (retrieval never serves unscoped), and a blank query fails closed to an
 * empty result without touching the database. The predicate's real row-level
 * behavior (foreign-curriculum chunks excluded, unlinked documents invisible,
 * ts_rank_cd ordering) is proven against real Postgres by ChunkLexicalSearchIT
 * on the CI lane (Docker).
 */
class ChunkLexicalRepositoryTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChunkLexicalRepository repository = new ChunkLexicalRepository(jdbc);

    @Test
    @DisplayName("null curriculum version id is rejected before any SQL runs (T-C07)")
    void nullScopeRejected() {
        assertThatThrownBy(() -> repository.search("moles", null, null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
    }

    @Test
    @DisplayName("blank or null query fails closed to an empty result without SQL")
    void blankQueryFailClosed() {
        assertThat(repository.search("", null, CV_ID, 5)).isEmpty();
        assertThat(repository.search("   ", null, CV_ID, 5)).isEmpty();
        assertThat(repository.search(null, null, CV_ID, 5)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("search SQL uses websearch_to_tsquery + ts_rank_cd over content_tsv with the EXISTS curriculum predicate")
    void sqlCarriesTsqueryRankingAndMandatoryPredicate() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search("electrolysis of molten lead bromide", null, CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("websearch_to_tsquery('english', ?)")
                .contains("c.embed_rev = ?")
                .contains("content_tsv @@ q.tsq")
                .contains("ts_rank_cd(c.content_tsv, q.tsq)")
                .contains("order by ts_rank_cd(c.content_tsv, q.tsq) desc, c.id")
                .contains("exists")
                .contains("exam_papers p")
                .contains("join subjects s on s.id = p.subject_id")
                .contains("s.curriculum_version_id = ?")
                .contains("p.question_paper_document_id = d.document_id")
                .contains("p.mark_scheme_document_id = d.document_id");
    }

    @Test
    @DisplayName("bind parameters carry query text, curriculum id, limit — never SQL-folded")
    void bindParametersCarryQueryScopeLimit() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search("moles", null, CV_ID, 7);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(args.getValue()).containsExactly("moles", ChunkVectorRepository.CURRENT_EMBED_REV, CV_ID, 7);
    }

    @Test
    @DisplayName("serving-eligible overload: null curriculum version id is rejected before any SQL (T-C07)")
    void servingEligibleNullScopeRejected() {
        assertThatThrownBy(() -> repository.searchServingEligible("moles", null, null, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
    }

    @Test
    @DisplayName("serving-eligible overload: blank query fails closed without SQL (T-C05)")
    void servingEligibleBlankQueryFailClosed() {
        assertThat(repository.searchServingEligible("", null, CV_ID, 5)).isEmpty();
        assertThat(repository.searchServingEligible(null, null, CV_ID, 5)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("T-C05/T-C27: serving-eligible SQL carries the paper-anchored VALIDATED gate — the card axis (subject branch) is deliberately absent here")
    void servingEligibleSqlCarriesValidatedGate() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchServingEligible("electrolysis of molten lead bromide", null, CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                // the boundary: only a VALIDATED owning paper serves
                .contains("p.validation_state = 'VALIDATED'")
                .contains("websearch_to_tsquery('english', ?)")
                .contains("c.embed_rev = ?")
                .contains("s.curriculum_version_id = ?")
                .contains("p.question_paper_document_id = d.document_id")
                .contains("p.mark_scheme_document_id = d.document_id")
                // the V33 subject branch is a VECTOR-surface-only path; the lexical
                // arm stays exam-paper-anchored, so paper-less card chunks can never
                // surface here at ANY validation state
                .doesNotContain("s2.id = c.subject_id");
        assertThat(countOccurrences(sql.getValue(), "validation_state = 'VALIDATED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("serving-eligible bind order: query, embed_rev, curriculum id, limit — never SQL-folded")
    void servingEligibleBindOrder() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchServingEligible("moles", null, CV_ID, 9);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("p.validation_state = 'VALIDATED'");
        assertThat(args.getValue()).containsExactly("moles", ChunkVectorRepository.CURRENT_EMBED_REV, CV_ID, 9);
    }

    @Test
    @DisplayName("document kinds fold into the SQL text as code-controlled enum names")
    void kindFilterFoldedIntoSqlText() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.search("moles", Set.of(Document.Kind.MARK_SCHEME, Document.Kind.QUESTION_PAPER),
                CV_ID, 5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("d.kind in ('MARK_SCHEME', 'QUESTION_PAPER')");

        // empty narrowing → no kind filter at all
        repository.search("moles", Set.of(), CV_ID, 5);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("d.kind in");
    }

    /** Occurrence count of a literal substring (the VALIDATED gate appears once — paper branch only). */
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
