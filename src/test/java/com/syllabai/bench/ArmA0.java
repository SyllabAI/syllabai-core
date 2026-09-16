package com.syllabai.bench;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.GraphKnowledgeRetriever;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import com.syllabai.tutor.ReciprocalRankFusion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T-C13 arm A0 (spec §2): KG-only deterministic retrieval — the CURRENT
 * production default and the baseline every other arm must beat.
 *
 * <p>Composition, faithfully reproduced over the frozen snapshot:</p>
 * <ol>
 *   <li>{@link GraphKnowledgeRetriever} (the production bean, unmodified):
 *       title-token matching over VALIDATED structure nodes, specificity
 *       ranking, single-token floor 0.50, code tie-break, maxTopics=5
 *       ({@code syllabai.tutor.max-topics:5}).</li>
 *   <li>{@link ReciprocalRankFusion} k=60 over the single KG candidate list —
 *       production runs this fusion with the vector list empty (0/2,333 chunks
 *       embedded), so the fused order equals the specificity order; running the
 *       real fusion (rather than assuming it) is deliberate: if fusion semantics
 *       ever change, this arm's next recorded run changes with them.</li>
 *   <li>NoReranker (v0) — nothing to do here by construction.</li>
 * </ol>
 *
 * <p>Output contract (recorded per query): the ranked matched topics (spec-point
 * codes) — A0's native, scored axis — plus prerequisite and misconception
 * signals (recorded, unscored), plus the RRF-fused order check. A0 emits NO
 * chunk evidence: the production evidence list comes from the vector retriever,
 * which is empty at 0/2,333 embedded. Per the ratified spec (§6) an empty
 * result from a runnable arm is a real zero on the chunk axis, NOT an
 * exclusion — that zero is the honest picture of today's serving default.</p>
 */
public final class ArmA0 {

    /** Production default: syllabai.tutor.max-topics:5 (KaRagService). */
    public static final int MAX_TOPICS = 5;
    /** Production default: syllabai.tutor.rrf-k:60 (ReciprocalRankFusion). */
    public static final int RRF_K = 60;

    public record Topic(String code, String title, double specificity, double fusedScore) {
    }

    public record A0Result(List<Topic> rankedTopics, List<String> fusedOrder,
                           List<String> prerequisiteCodes, List<String> misconceptionCodes) {
    }

    private final GraphKnowledgeRetriever retriever;
    private final ReciprocalRankFusion fusion;
    private final BenchGraph benchGraph;
    private final BenchSnapshot snapshot;
    /**
     * T-C07: production retrieval is curriculum-scoped, so the arm is too. The
     * bench scope's intent surface IS the frozen snapshot surface (the
     * snapshot's own structure-node ids, which are exactly the snap-001
     * VALIDATED 4CH1 corpus); the identity is a deterministic UUIDv3-style
     * name derived from the snapshot id — recorded in the run manifest, never
     * used for SQL (the bench never touches the database).
     */
    private final CurriculumScope scope;

    public ArmA0(BenchSnapshot snapshot) {
        this.snapshot = snapshot;
        this.benchGraph = new BenchGraph(snapshot);
        this.retriever = benchGraph.retriever();
        this.fusion = new ReciprocalRankFusion(RRF_K);
        java.util.Set<java.util.UUID> surface = new java.util.HashSet<>();
        for (com.syllabai.knowledge.KnowledgeNode node : snapshot.structureNodes()) {
            surface.add(node.id());
        }
        this.scope = new CurriculumScope(
                java.util.UUID.nameUUIDFromBytes(
                        ("bench-scope|" + snapshot.snapshotVersion()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "bench-snap-001",
                surface);
    }

    public CurriculumScope scope() {
        return scope;
    }

    public A0Result run(String query) {
        KnowledgeContext context = retriever.retrieve(query, MAX_TOPICS, scope);

        Map<UUID, String> codeById = new LinkedHashMap<>();
        List<EvidenceItem> candidates = new ArrayList<>();
        for (KnowledgeContext.MatchedTopic topic : context.topics()) {
            codeById.put(topic.nodeId(), topic.code());
            candidates.add(EvidenceItem.fromNode(topic.nodeId(), topic.code(), "SUBTOPIC",
                    topic.title(), null, topic.matchScore()));
        }
        // Production A0: fuse [kgList, emptyVectorList]. The empty list is passed
        // explicitly so the arm's shape mirrors the production call exactly.
        List<EvidenceItem> fused = fusion.fuse(List.of(candidates, List.of()));
        List<String> fusedOrder = fused.stream().map(EvidenceItem::nodeCode).toList();

        List<Topic> ranked = new ArrayList<>();
        Map<String, Double> fusedByCode = new LinkedHashMap<>();
        for (EvidenceItem item : fused) {
            fusedByCode.put(item.nodeCode(), item.fusedScore());
        }
        for (KnowledgeContext.MatchedTopic topic : context.topics()) {
            ranked.add(new Topic(topic.code(), topic.title(), topic.matchScore(),
                    fusedByCode.getOrDefault(topic.code(), 0.0)));
        }

        List<String> prereqs = context.prerequisites().stream()
                .map(p -> snapshot.nodeById(p.nodeId()) == null
                        ? null : snapshot.nodeById(p.nodeId()).code())
                .filter(c -> c != null)
                .distinct()
                .toList();
        List<String> misconceptions = context.misconceptions().stream()
                .map(m -> snapshot.nodeById(m.nodeId()) == null
                        ? null : snapshot.nodeById(m.nodeId()).code())
                .filter(c -> c != null)
                .distinct()
                .toList();

        return new A0Result(List.copyOf(ranked), fusedOrder, prereqs, misconceptions);
    }
}
