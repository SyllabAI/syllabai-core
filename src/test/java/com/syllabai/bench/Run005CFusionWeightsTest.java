package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The T-C65 fusion-weights seam: {@code BENCH_FUSION_WEIGHTS} selects the
 * SHIPPED plan §7 posture through the shipped 4-arg fabric ctor, absence
 * keeps the recorded unweighted posture (byte-identical by construction),
 * unknown specs fail closed, and the two levers (reranker × weights) are
 * mutually exclusive — one lever at a time, per the pre-registration's
 * attribution guard.
 */
class Run005CFusionWeightsTest {

    @Test
    @DisplayName("absent/blank spec -> null map (the unweighted 3-arg posture, byte-identical)")
    void absentSpecIsNull() {
        assertThat(Run005C.fusionWeightsForSpec(null)).isNull();
        assertThat(Run005C.fusionWeightsForSpec("")).isNull();
        assertThat(Run005C.fusionWeightsForSpec("   ")).isNull();
    }

    @Test
    @DisplayName("plan_v2 -> exactly the shipped PLAN_V2_WEIGHTS map (one source of truth)")
    void planV2IsTheShippedMap() {
        Map<EvidenceItem.EvidenceSource, Double> weights = Run005C.fusionWeightsForSpec("plan_v2");
        assertThat(weights).isSameAs(RetrievalFabric.PLAN_V2_WEIGHTS);
        assertThat(Run005C.fusionWeightsForSpec("  plan_v2 ")).isSameAs(RetrievalFabric.PLAN_V2_WEIGHTS);
    }

    @Test
    @DisplayName("unknown spec fails closed — never silently unweighted")
    void unknownSpecFailsClosed() {
        assertThatThrownBy(() -> Run005C.fusionWeightsForSpec("PLAN_V2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_FUSION_WEIGHTS");
        assertThatThrownBy(() -> Run005C.fusionWeightsForSpec("unweighted"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.fusionWeightsForSpec("lexical_precision"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("one lever at a time: reranker x weights together is a composition error")
    void leversAreMutuallyExclusive() {
        EvidenceReranker reranker = new LexicalPrecisionReranker();
        Map<EvidenceItem.EvidenceSource, Double> weights = RetrievalFabric.PLAN_V2_WEIGHTS;
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatCode(() -> Run005C.requireSingleLever(reranker, null)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, weights)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the shipped posture the seam exposes is the plan §7 map the serving path runs")
    void shippedMapMatchesPlanSection7() {
        assertThat(RetrievalFabric.PLAN_V2_WEIGHTS)
                .isEqualTo(ReciprocalRankFusion.PLAN_V2_WEIGHTS)
                .containsEntry(EvidenceItem.EvidenceSource.NOTE, 1.0)
                .containsEntry(EvidenceItem.EvidenceSource.SYLLABUS, 0.9)
                .containsEntry(EvidenceItem.EvidenceSource.QUESTION_PAPER, 0.8)
                .containsEntry(EvidenceItem.EvidenceSource.MARK_SCHEME, 0.6)
                .containsEntry(EvidenceItem.EvidenceSource.CARD, 0.3);
    }
}
