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
 * T-C31: the empty-path diagnostic SQL mirrors the gates it names. The
 * drift-guard is the point — {@code servingEligible} counts rows with the
 * EXACT {@code searchServingEligible} WHERE predicate (whitespace-normalized
 * containment of {@link ChunkVectorRepository#SCOPE_EXISTS_VALIDATED} in the
 * serving SQL), and the funnel's scope stage uses the gate-free
 * {@link ChunkVectorRepository#SCOPE_EXISTS_NO_VALIDATION} fragment. If either
 * serving query ever changes its predicate without updating the fragment (or
 * vice versa), these tests fail instead of the classification silently lying.
 */
class ChunkVectorRepositoryDiagnoseTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChunkVectorRepository repository = new ChunkVectorRepository(jdbc);

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("drift-guard: serving SQL contains SCOPE_EXISTS_VALIDATED (normalized); neutral SQL contains the gate-free fragment")
    void servingSqlContainsValidatedFragment() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repository.searchServingEligible(new float[] {0.1f}, null, CV_ID, 5);
        ArgumentCaptor<String> serving = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(serving.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(normalized(serving.getValue()))
                .contains(normalized(ChunkVectorRepository.SCOPE_EXISTS_VALIDATED))
                .doesNotContain(normalized(ChunkVectorRepository.SCOPE_EXISTS_NO_VALIDATION));

        repository.search(new float[] {0.1f}, null, CV_ID, 5);
        ArgumentCaptor<String> neutral = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(neutral.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(normalized(neutral.getAllValues().get(1)))
                .contains(normalized(ChunkVectorRepository.SCOPE_EXISTS_NO_VALIDATION))
                .doesNotContain("validation_state");
    }

    @Test
    @DisplayName("funnel SQL: scope-only stage (no validation gates), embed_rev filter present; eligible SQL: the T-C20 gates on both branches")
    void diagnoseSqlMirrorsTheGates() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new SearchEmptyDiagnostics(0, 0, 0, 0));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class))).thenReturn(0L);

        repository.diagnoseEmpty(Document.Kind.EXTERNAL_QUESTIONS, CV_ID);

        ArgumentCaptor<String> funnel = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(funnel.capture(), any(RowMapper.class),
                any(Object[].class));
        assertThat(normalized(funnel.getValue()))
                .contains("count(*) filter (where c.embedding is not null")
                .contains("c.embed_rev = ?")
                .contains(normalized(ChunkVectorRepository.SCOPE_EXISTS_NO_VALIDATION))
                .contains("d.kind = 'EXTERNAL_QUESTIONS'")
                .doesNotContain("validation_state");

        ArgumentCaptor<String> eligible = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(eligible.capture(),
                org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class));
        assertThat(normalized(eligible.getValue()))
                .contains("p.validation_state = 'VALIDATED'")
                .contains("d.validation_state = 'VALIDATED'")
                .contains(normalized(ChunkVectorRepository.SCOPE_EXISTS_VALIDATED));
    }

    @Test
    @DisplayName("bind order: funnel binds (embed_rev, cv, cv); eligible binds (embed_rev, cv, cv)")
    void bindOrder() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new SearchEmptyDiagnostics(0, 0, 0, 0));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class))).thenReturn(0L);

        repository.diagnoseEmpty(null, CV_ID);

        ArgumentCaptor<Object[]> funnelBinds = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), any(RowMapper.class),
                funnelBinds.capture());
        assertThat(funnelBinds.getValue()).containsExactly(
                (Object) ChunkVectorRepository.CURRENT_EMBED_REV, CV_ID, CV_ID);

        ArgumentCaptor<Object[]> eligibleBinds = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class), eligibleBinds.capture());
        assertThat(eligibleBinds.getValue()).containsExactly(
                (Object) ChunkVectorRepository.CURRENT_EMBED_REV, CV_ID, CV_ID);
    }

    @Test
    @DisplayName("null curriculum version id is rejected before any SQL runs (T-C07)")
    void nullScopeRejected() {
        assertThatThrownBy(() -> repository.diagnoseEmpty(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never run unscoped");
    }

    @Test
    @DisplayName("eligible count null (no row) is treated as 0 — never a label-ending NPE")
    void nullEligibleCountBecomesZero() {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new SearchEmptyDiagnostics(3, 3, 3, 0));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class))).thenReturn(null);

        SearchEmptyDiagnostics d = repository.diagnoseEmpty(null, CV_ID);

        assertThat(d.servingEligible()).isZero();
        assertThat(d.cause()).isEqualTo(SearchEmptyCause.VALIDATION_GATE_EMPTY);
    }
}
