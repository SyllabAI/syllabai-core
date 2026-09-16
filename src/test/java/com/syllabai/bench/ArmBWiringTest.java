package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T-C14 benchmark adapter wiring (spec §6): the bench arm drives the
 * production lexical provider with the bench scope, emits portable refs in the
 * gold format, and its boundary audit is fail-closed (any non-VALIDATED paper
 * state in the ranked list is a violation).
 */
class ArmBWiringTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.fromString("00000000-0000-0000-0000-00000000beef"), "bench-snap-001", Set.of());

    private static RetrievalCandidate candidate(String documentId, int ordinal, double score) {
        return new RetrievalCandidate("bm25", UUID.randomUUID(), documentId, 1,
                UUID.randomUUID().toString(), null, null, "content", score, null, null,
                Map.of("chunk_index", String.valueOf(ordinal), "document_kind", "MARK_SCHEME"));
    }

    @Test
    @DisplayName("the arm drives the production provider with the bench scope and limit")
    void drivesProductionProvider() {
        RetrievalProvider provider = mock(RetrievalProvider.class);
        when(provider.retrieve(any())).thenReturn(List.of());
        ArmB arm = new ArmB(provider, SCOPE, Map.of());
        arm.run("titration", 20);
        ArgumentCaptor<StructuredRetrievalQuery> q =
                ArgumentCaptor.forClass(StructuredRetrievalQuery.class);
        verify(provider).retrieve(q.capture());
        assertThat(q.getValue().normalizedQuery()).isEqualTo("titration");
        assertThat(q.getValue().curriculumVersionId()).isEqualTo(SCOPE.curriculumVersionId());
        assertThat(q.getValue().limit()).isEqualTo(20);
    }

    @Test
    @DisplayName("candidates map to portable gold refs (checksum:ordinal) with native scores")
    void portableRefs() {
        RetrievalProvider provider = mock(RetrievalProvider.class);
        when(provider.retrieve(any(StructuredRetrievalQuery.class))).thenReturn(List.of(
                candidate("aaa111", 3, 0.31),
                candidate("bbb222", 0, 0.12)));
        ArmB arm = new ArmB(provider, SCOPE, Map.of("aaa111", "VALIDATED", "bbb222", "VALIDATED"));
        ArmB.BResult result = arm.run("moles", 20);
        assertThat(result.rankedRefs()).containsExactly("aaa111:3", "bbb222:0");
        assertThat(result.scores()).containsExactly(0.31, 0.12);
        assertThat(result.boundaryViolations()).isZero();
    }

    @Test
    @DisplayName("boundary audit: a non-VALIDATED paper in the ranked list is a violation (hard fail)")
    void boundaryAuditFailsClosed() {
        Map<String, String> states = Map.of(
                "chk-ok", "VALIDATED",
                "chk-suggested", "SUGGESTED",
                "chk-flagged", "FLAGGED");
        List<String> violations = ArmB.audit(
                List.of("chk-ok:0", "chk-suggested:2", "chk-flagged:9"), states);
        assertThat(violations).containsExactly("chk-suggested:2@SUGGESTED", "chk-flagged:9@FLAGGED");
        assertThat(ArmB.audit(List.of("chk-ok:0"), states)).isEmpty();
    }

    @Test
    @DisplayName("unknown paper state in the audit map is a violation (fail-closed, never assume)")
    void unknownStateIsAViolation() {
        List<String> violations = ArmB.audit(List.of("chk-unknown:1"), Map.of());
        assertThat(violations).containsExactly("chk-unknown:1@null");
    }
}
