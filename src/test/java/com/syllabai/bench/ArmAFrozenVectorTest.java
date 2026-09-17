package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.tutor.EvidenceItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hermetic unit tests for the arm A replay pieces (session 94): the frozen
 * query-vector provider contract (stripped-text lookup, clone semantics,
 * fail-closed missing-vector behavior that the production retriever can NOT
 * silently degrade), the portable identity reconstruction, and the post-hoc
 * compliant view. No DB, no network — the DB-dependent path is covered by
 * {@link ArmAReplayIT} on the CI lane.
 */
class ArmAFrozenVectorTest {

    private static float[] vector(float seed) {
        float[] v = new float[768];
        for (int i = 0; i < 768; i++) {
            v[i] = seed + i * 0.001f;
        }
        return v;
    }

    @Test
    @DisplayName("frozen provider: serves the artifact vector for the stripped query text")
    void servesFrozenVector() {
        Map<String, float[]> byStripped = new LinkedHashMap<>();
        byStripped.put("what is titration?", vector(0.1f));
        ArmA.FrozenQueryVectors provider = new ArmA.FrozenQueryVectors("fake-embed", 768, byStripped);

        assertThat(provider.model()).isEqualTo("fake-embed");
        assertThat(provider.dimension()).isEqualTo(768);
        float[] out = provider.embedQuery("  what is titration?  ");
        assertThat(out).hasSize(768);
        assertThat(out[0]).isEqualTo(0.1f);
        // clone semantics: mutating the returned vector must not touch the frozen state
        out[0] = 9.9f;
        assertThat(provider.embedQuery("what is titration?")[0]).isEqualTo(0.1f);
    }

    @Test
    @DisplayName("frozen provider: missing query text fails closed with a NON-degradable exception")
    void missingVectorFailsClosed() {
        ArmA.FrozenQueryVectors provider = new ArmA.FrozenQueryVectors(
                "fake-embed", 768, Map.of("known", vector(0.2f)));

        // MissingFrozenVectorException must NOT be an IllegalStateException:
        // ContentVectorRetriever degrades that to an honest empty list, which
        // would silently corrupt the measurement.
        assertThatThrownBy(() -> provider.embedQuery("unknown query"))
                .isInstanceOf(ArmA.MissingFrozenVectorException.class)
                .isNotInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.embedQuery(null))
                .isInstanceOf(ArmA.MissingFrozenVectorException.class);
        assertThatThrownBy(() -> provider.embedDocument("anything"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider.embedDocuments(List.of("a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("portable identity: documentId:chunkIndex reconstruction from a chunk hit")
    void chunkRefReconstruction() {
        EvidenceItem hit = EvidenceItem.fromChunk(
                java.util.UUID.randomUUID(), "doc-checksum-abc", 1,
                java.util.UUID.randomUUID(), 7, "MARK_SCHEME", "content",
                1, 2, List.of(), "fake-embed", 0.87);
        assertThat(ArmA.chunkRef(hit)).isEqualTo("doc-checksum-abc:7");

        EvidenceItem malformed = EvidenceItem.fromChunk(
                null, null, 1, null, 0, "MARK_SCHEME", "content",
                null, null, List.of(), "fake-embed", 0.5);
        assertThatThrownBy(() -> ArmA.chunkRef(malformed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("compliant view: keeps VALIDATED hits in order, drops the rest, audit reused verbatim")
    void compliantViewFilters() {
        Map<String, String> states = new LinkedHashMap<>();
        states.put("doc-v", "VALIDATED");
        states.put("doc-s", "SUGGESTED");
        List<String> served = List.of("doc-s:0", "doc-v:3", "doc-s:9", "doc-v:1", "doc-v:11");
        List<Double> scores = List.of(0.9, 0.8, 0.7, 0.6, 0.5);
        ArmA.AResult servedResult = new ArmA.AResult(served, scores, 3,
                List.of("doc-s:0@SUGGESTED", "doc-s:9@SUGGESTED"));

        ArmA.AResult compliant = ArmA.compliantView(servedResult, states);
        assertThat(compliant.rankedRefs()).containsExactly("doc-v:3", "doc-v:1", "doc-v:11");
        assertThat(compliant.scores()).containsExactly(0.8, 0.6, 0.5);
        assertThat(compliant.boundaryViolations()).isZero();

        // the boundary audit itself stays ArmB's, verbatim
        assertThat(ArmB.audit(served, states)).hasSize(2);
    }
}
