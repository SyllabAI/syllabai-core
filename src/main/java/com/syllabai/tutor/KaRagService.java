package com.syllabai.tutor;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.syllabai.shared.events.TutorAnsweredEvent;

/**
 * KA-RAG orchestration (T-024, Master Spec §13): intent → KG context →
 * hybrid retrieval → fusion → rerank → grounded generation → citations →
 * telemetry. The pipeline's contract with itself:
 *
 * <ul>
 *   <li>intent is deterministic (no LLM entity invention, §7);</li>
 *   <li>retrieval is hybrid — KG and vector evidence are fused by rank, so
 *       neither source is the sole mechanism;</li>
 *   <li>generation is grounded — with zero surviving evidence the service
 *       refuses (deterministically, no LLM call) rather than hallucinating;</li>
 *   <li>every answer emits a {@code TutorAnsweredEvent} so the research
 *       record (§18) captures the full retrieval provenance.</li>
 * </ul>
 */
@Service
public class KaRagService {

    private static final Logger log = LoggerFactory.getLogger(KaRagService.class);

    static final String REFUSAL = """
            I can't answer that from the validated course material yet. There is no
            matching specification topic or source document for this question, so
            answering would mean guessing — which SyllabAI never does. Try naming the
            topic (e.g. "moles", "bonding", "equilibria") or ask your teacher to
            ingest the relevant material.""";

    private final KnowledgeRetriever knowledgeRetriever;
    private final VectorRetriever vectorRetriever;
    private final CurriculumScopeResolver curriculumScopes;
    private final ReciprocalRankFusion fusion;
    private final EvidenceReranker reranker;
    private final ContextAssembler contextAssembler;
    private final TutorGenerator generator;
    private final CitationResolver citationResolver;
    private final ApplicationEventPublisher events;

    private final int maxTopics;
    private final int vectorCandidates;
    private final int evidenceLimit;

    public KaRagService(KnowledgeRetriever knowledgeRetriever,
                        VectorRetriever vectorRetriever,
                        CurriculumScopeResolver curriculumScopes,
                        ReciprocalRankFusion fusion,
                        EvidenceReranker reranker,
                        ContextAssembler contextAssembler,
                        TutorGenerator generator,
                        CitationResolver citationResolver,
                        ApplicationEventPublisher events,
                        @Value("${syllabai.tutor.max-topics:5}") int maxTopics,
                        @Value("${syllabai.tutor.vector-candidates:12}") int vectorCandidates,
                        @Value("${syllabai.tutor.evidence-limit:6}") int evidenceLimit) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.vectorRetriever = vectorRetriever;
        this.curriculumScopes = curriculumScopes;
        this.fusion = fusion;
        this.reranker = reranker;
        this.contextAssembler = contextAssembler;
        this.generator = generator;
        this.citationResolver = citationResolver;
        this.events = events;
        this.maxTopics = Math.max(1, maxTopics);
        this.vectorCandidates = Math.max(1, vectorCandidates);
        this.evidenceLimit = Math.max(1, evidenceLimit);
    }

    /**
     * @param learnerId asking learner (null allowed for anonymous preview)
     * @param query     the learner's question
     * @return grounded answer with citations, or a deterministic refusal
     */
    public TutorAnswerView ask(UUID learnerId, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        long startedAt = System.nanoTime();

        // 0. active curriculum scope (T-C07, fail-closed): unresolved scope ⇒
        // both retrieval surfaces stay empty ⇒ the deterministic refusal below.
        // Never serve across curricula; never serve unscoped.
        CurriculumScope scope = curriculumScopes.resolveActive(learnerId).orElse(null);

        // 1. deterministic intent + KG context
        KnowledgeRetriever.KnowledgeContext knowledge = scope == null
                ? new KnowledgeRetriever.KnowledgeContext(List.of(), List.of(), List.of())
                : knowledgeRetriever.retrieve(query, maxTopics, scope);

        // 2. hybrid retrieval: KG evidence + vector evidence
        List<EvidenceItem> kgCandidates = knowledge.topics().stream()
                .map(topic -> EvidenceItem.fromNode(topic.nodeId(), topic.code(),
                        "TOPIC", topic.title(), null, topic.matchScore()))
                .toList();
        List<EvidenceItem> vectorCandidatesList = scope == null
                ? List.of()
                : vectorRetriever.retrieve(query, vectorCandidates, scope);

        // 3. rank fusion (both sources contribute; neither dominates)
        List<EvidenceItem> fused = fusion.fuse(List.of(kgCandidates, vectorCandidatesList));

        // 4. rerank + cap (v0: NoReranker keeps the fused order)
        List<EvidenceItem> evidence = reranker.rerank(query, fused)
                .stream()
                .limit(evidenceLimit)
                .map(item -> item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE
                        ? item
                        : item.withTopicIds(matchedTopicIds(knowledge)))
                .toList();

        // 5. grounding gate: no evidence → refuse, deterministically, no LLM
        TutorGenerator.GeneratedAnswer generated;
        boolean refused = evidence.isEmpty();
        ContextAssembler.TutorContext context = null;
        if (refused) {
            generated = new TutorGenerator.GeneratedAnswer(REFUSAL, null, "deterministic-refusal");
        } else {
            context = contextAssembler.assemble(knowledge, evidence, learnerId);
            generated = generator.generate(query, context);
        }
        // V23 signal provenance: the deterministic policy decision for this ask
        String interventionType = context == null || context.interventionPlan() == null
                ? null : context.interventionPlan().type() == null
                ? null : context.interventionPlan().type().name();

        List<CitationResolver.Citation> citations = citationResolver.resolve(evidence);
        double latencyMs = (System.nanoTime() - startedAt) / 1_000_000.0;

        events.publishEvent(new TutorAnsweredEvent(
                learnerId, query.strip(), matchedTopicIds(knowledge), evidence.size(),
                evidence.stream().map(item -> item.source().name()).toList(),
                refused, generated.model(), GroundedTutorGenerator.promptIdentity(),
                latencyMs, Instant.now(), interventionType));

        log.info("KA-RAG answered ({} evidence, {} topics, refused={}, {} ms)",
                evidence.size(), knowledge.topics().size(), refused,
                String.format(java.util.Locale.ROOT, "%.1f", latencyMs));
        return TutorAnswerView.of(generated.answer(), citations,
                knowledge.topics().stream()
                        .map(topic -> new TutorAnswerView.TopicMatch(
                                topic.code(), topic.title(), topic.matchScore()))
                        .toList(),
                evidence.size(), generated.model(), generated.provider(), refused, latencyMs);
    }

    private static List<UUID> matchedTopicIds(KnowledgeRetriever.KnowledgeContext knowledge) {
        return knowledge.topics().stream()
                .map(KnowledgeRetriever.KnowledgeContext.MatchedTopic::nodeId)
                .toList();
    }
}
