package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.EvidenceItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-kind RRF weights (R4, plan §7): the weighted fusion scales each rank's
 * contribution by the candidate's evidence-source weight without touching
 * within-arm rank order, the unweighted posture stays bit-identical, and the
 * shipped plan weights are exactly the documented constants.
 */
class WeightedRetrievalFabricTest {

    private static final UUID CHUNK_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CHUNK_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final CurriculumScope SCOPE =
            new CurriculumScope(UUID.fromString("00000000-0000-0000-0000-00000000c001"), "4CH1", Set.of());

    private static RetrievalCandidate chunk(UUID chunkId, String kind) {
        return new RetrievalCandidate("pgvector", null, "doc-1", 1, chunkId.toString(),
                null, null, "content " + chunkId, 0.5, null, null,
                Map.of("chunk_index", "0", "document_kind", kind));
    }

    /** One-arm fabric shortcut: candidates flow through boundary + fusion untouched. */
    private static RetrievalFabric fabric(Map<EvidenceItem.EvidenceSource, Double> weights) {
        return new RetrievalFabric(
                List.of(new RetrievalProvider() {
                    @Override
                    public String id() {
                        return "pgvector";
                    }

                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
                        return List.of(chunk(CHUNK_A, "EXTERNAL_NOTES"), chunk(CHUNK_B, "EXTERNAL_QUESTIONS"));
                    }
                }),
                new ReciprocalRankFusion(60),
                candidate -> true,
                weights);
    }

    private static StructuredRetrievalQuery query() {
        return StructuredRetrievalQuery.of("electrolysis", SCOPE, 10);
    }

    @Test
    @DisplayName("weights reorder deterministically: NOTE(1.0) outranks CARD(0.3) from the same arm")
    void weightsReorder() {
        List<RetrievalCandidate> fused = fabric(RetrievalFabric.PLAN_V2_WEIGHTS)
                .retrieveCandidates(query());
        // same arm, same ranks: the note's full-weight rank-1 contribution beats
        // the card's 0.3-weighted rank-2 contribution
        assertThat(fused.get(0).evidenceLocator()).isEqualTo(CHUNK_A.toString());
        assertThat(fused.get(1).evidenceLocator()).isEqualTo(CHUNK_B.toString());
    }

    @Test
    @DisplayName("null weights == unweighted posture (bench replays stay bit-identical)")
    void nullWeightsAreUnweighted() {
        RetrievalProvider scripted = new RetrievalProvider() {
            @Override
            public String id() {
                return "pgvector";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
                return List.of(chunk(CHUNK_A, "EXTERNAL_NOTES"), chunk(CHUNK_B, "EXTERNAL_QUESTIONS"));
            }
        };
        List<RetrievalCandidate> weighted = fabric(null).retrieveCandidates(query());
        List<RetrievalCandidate> unweighted = new RetrievalFabric(
                List.of(scripted), new ReciprocalRankFusion(60), candidate -> true)
                .retrieveCandidates(query());
        assertThat(weighted).hasSameSizeAs(unweighted);
        // direct fuser check — identical fused scores with no weights
        ReciprocalRankFusion fuser = new ReciprocalRankFusion(60);
        EvidenceItem a = EvidenceItem.fromChunk(null, "d1", 1, CHUNK_A, 0, "EXTERNAL_NOTES",
                "note text", null, null, List.of(), null, 0.9);
        EvidenceItem b = EvidenceItem.fromChunk(null, "d1", 1, CHUNK_B, 1, "EXTERNAL_QUESTIONS",
                "card text", null, null, List.of(), null, 0.8);
        double plainA = fuser.fuse(List.of(List.of(a, b))).get(0).fusedScore();
        double weightedA = fuser.fuse(List.of(List.of(a, b)), item -> 1.0).get(0).fusedScore();
        assertThat(weightedA).isEqualTo(plainA);
    }

    @Test
    @DisplayName("weights scale contributions: a 0.3-weighted rank-1 can lose to a 1.0 rank-2")
    void weightsScaleContributions() {
        ReciprocalRankFusion fuser = new ReciprocalRankFusion(60);
        EvidenceItem card = EvidenceItem.fromChunk(null, "d1", 1, CHUNK_A, 0, "EXTERNAL_QUESTIONS",
                "card", null, null, List.of(), null, 0.9);
        EvidenceItem note = EvidenceItem.fromChunk(null, "d1", 1, CHUNK_B, 1, "EXTERNAL_NOTES",
                "note", null, null, List.of(), null, 0.8);
        // card is rank 0 (k+1 denominator), note rank 1 (k+2):
        // weighted → 0.3/61 vs 1.0/62 → note wins; unweighted → card's 1/61 beats note's 1/62
        List<EvidenceItem> weighted = fuser.fuse(List.of(List.of(card, note)),
                item -> RetrievalFabric.PLAN_V2_WEIGHTS.getOrDefault(item.source(), 1.0));
        assertThat(weighted.get(0).chunkId()).isEqualTo(CHUNK_B);
        List<EvidenceItem> unweighted = fuser.fuse(List.of(List.of(card, note)));
        assertThat(unweighted.get(0).chunkId()).isEqualTo(CHUNK_A);
    }

    @Test
    @DisplayName("negative weights are a composition error (fail-closed)")
    void negativeWeightsRejected() {
        assertThatThrownBy(() -> fabric(Map.of(EvidenceItem.EvidenceSource.NOTE, -1.0))
                .retrieveCandidates(query()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("plan §7 weights ship exactly as documented")
    void planWeights() {
        assertThat(RetrievalFabric.PLAN_V2_WEIGHTS)
                .containsEntry(EvidenceItem.EvidenceSource.NOTE, 1.0)
                .containsEntry(EvidenceItem.EvidenceSource.SYLLABUS, 0.9)
                .containsEntry(EvidenceItem.EvidenceSource.QUESTION_PAPER, 0.8)
                .containsEntry(EvidenceItem.EvidenceSource.TEXTBOOK, 0.7)
                .containsEntry(EvidenceItem.EvidenceSource.MARK_SCHEME, 0.6)
                .containsEntry(EvidenceItem.EvidenceSource.CARD, 0.3);
        assertThat(RetrievalFabric.PLAN_V2_WEIGHTS).hasSize(6);
    }
}
