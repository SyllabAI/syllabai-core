package com.syllabai.tutor;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *       that neither source is the sole mechanism;</li>
 *   <li>generation is grounded — with zero surviving evidence the service
 *       refuses (deterministically, no LLM call) rather than hallucinating;</li>
 *   <li>a parsed paper-question identity that binds zero validated anchors
 *       refuses deterministically too (the fail-open guard, 09-27
 *       adjudication direction (a)) — generic retrieval never answers a
 *       named paper question the corpus cannot bind;</li>
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

    /**
     * The fail-open guard's refusal (09-27 adjudication, direction (a)): the
     * ask carried a complete paper-question identity but zero validated
     * anchors bound it. The echoed identity ({@code %s}) makes the refusal
     * verifiable on the surface — the answer never cites wrong-paper chunks.
     */
    static final String PAPER_IDENTITY_REFUSAL = """
            I can't answer that from the validated course material yet. I could not
            find %s in the validated corpus — the paper or question may not be
            ingested yet, or it may still be awaiting validation. Answering would
            mean guessing, which SyllabAI never does. Try naming the topic (e.g.
            "moles", "bonding", "equilibria") or ask your teacher to ingest the
            relevant material.""";

    private final KnowledgeRetriever knowledgeRetriever;
    private final VectorRetriever vectorRetriever;
    private final PaperQuestionResolver paperQuestionResolver;
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

    /** Working-memory retrieval budgets (s139): the enriched query keeps the
     *  final question plus the most recent turns only — retrieval needs the
     *  topical keywords of the immediately preceding exchange, and older
     *  turns add drift, not signal. The question itself is never truncated. */
    static final int RETRIEVAL_WINDOW_TURNS = 4;
    static final int RETRIEVAL_QUERY_MAX_CHARS = 1200;

    public KaRagService(KnowledgeRetriever knowledgeRetriever,
                        VectorRetriever vectorRetriever,
                        PaperQuestionResolver paperQuestionResolver,
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
        this.paperQuestionResolver = paperQuestionResolver;
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
     * Single-turn ask (anchored/legacy callers): identical to an ask with no
     * conversation history and no session persistence.
     *
     * @param learnerId asking learner (null allowed for anonymous preview)
     * @param query     the learner's question
     * @return grounded answer with citations, or a deterministic refusal
     */
    public TutorAnswerView ask(UUID learnerId, String query) {
        return ask(learnerId, query, List.of(), null);
    }

    /**
     * Conversational ask (s139 working memory) without session persistence —
     * the CLA/SmartLesson surfaces and tests.
     */
    public TutorAnswerView ask(UUID learnerId, String question, List<ConversationTurn> history) {
        return ask(learnerId, question, history, null);
    }

    /**
     * Conversational ask (s139 working memory) with a §22 session anchor
     * (s140): the session id rides the {@code TutorAnsweredEvent} for §3.5
     * research linkage — the PIPELINE itself stays session-unaware (persistence
     * is the controller's concern; scope, retrieval and generation are
     * identical whether the exchange is stored or not).
     *
     * @param learnerId asking learner (null allowed for anonymous preview)
     * @param question  the learner's question (the turn to answer now)
     * @param history   prior turns of this chat, oldest first (client-supplied,
     *                 sanitized here before any pipeline use)
     * @param sessionId §22 tutor session this exchange belongs to (null = an
     *                 unpersisted ask — pre-s140 clients, CLA, SmartLesson)
     * @return grounded answer with citations, or a deterministic refusal
     */
    public TutorAnswerView ask(UUID learnerId, String question, List<ConversationTurn> history,
                               UUID sessionId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        List<ConversationTurn> turns = ConversationTurn.sanitize(history);
        String query = question.strip();
        long startedAt = System.nanoTime();

        // 0. active curriculum scope (T-C07, fail-closed): unresolved scope ⇒
        // both retrieval surfaces stay empty ⇒ the deterministic refusal below.
        // Never serve across curricula; never serve unscoped.
        CurriculumScope scope = curriculumScopes.resolveActive(learnerId).orElse(null);

        // 0.5 working memory (s139): follow-ups like "why is that?" carry no
        // topic vocabulary of their own — retrieval runs on the question
        // enriched with the most recent turns (deterministic concatenation:
        // the KG intent matcher stays a token-overlap function, no LLM rewrite,
        // and the added tokens can only surface topics the scope already
        // serves). Generation still sees the RAW question + the conversation.
        String retrievalQuery = retrievalQuery(query, turns);

        // 1. deterministic intent + KG context
        KnowledgeRetriever.KnowledgeContext knowledge = scope == null
                ? new KnowledgeRetriever.KnowledgeContext(List.of(), List.of(), List.of())
                : knowledgeRetriever.retrieve(retrievalQuery, maxTopics, scope);

        // 2. hybrid retrieval: KG evidence + vector evidence
        List<EvidenceItem> kgCandidates = knowledge.topics().stream()
                .map(topic -> EvidenceItem.fromNode(topic.nodeId(), topic.code(),
                        "TOPIC", topic.title(), null, topic.matchScore()))
                .toList();
        List<EvidenceItem> vectorCandidatesList = scope == null
                ? List.of()
                : vectorRetriever.retrieve(retrievalQuery, vectorCandidates, scope);

        // 2.5 deterministic paper-question lead evidence (plan §7 lead items):
        // a paper-style ask ("explain question 10 from june 2019 paper 2")
        // binds session+paper+number by metadata and pins the exact question's
        // chunks at the HEAD of the pool — outside RRF, where per-kind weights
        // would rank identity cards (0.3) below everything else. The verdict
        // travels with the items: a complete identity that bound nothing is
        // the fail-open guard's trigger (step 4.5), not a pass-through. The
        // identity is parsed from the retrieval query (s139: with history the
        // enriched query still carries the current turn's paper vocabulary —
        // single-turn asks are byte-identical to the pre-s139 behavior).
        PaperQuestionResolver.Resolution resolution = scope == null
                ? PaperQuestionResolver.Resolution.notPaperAsk()
                : paperQuestionResolver.resolveWithVerdict(retrievalQuery, scope);
        List<EvidenceItem> pinned = resolution.items();

        // 3. rank fusion (plan §7 per-kind weights — the P3 serving posture:
        // NOTE 1.0 > SYLLABUS 0.9 > QUESTION_PAPER 0.8 > TEXTBOOK 0.7 >
        // MARK_SCHEME 0.6 > CARD 0.3; KG topic anchors weigh 1.0, so their
        // influence is unchanged. Rank order inside each list is untouched;
        // the unweighted 1-arg fuse stays bit-identical for bench replays)
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(
                List.of(kgCandidates, vectorCandidatesList));

        // 4. lead-first merge, dedup (a pinned chunk may also surface via the
        //    vector arm — the pinned lead wins), rerank + cap (v0: NoReranker
        //    keeps the merged order). The reranker scores against the enriched
        //    query so follow-ups rank their own evidence correctly.
        List<EvidenceItem> reranked = reranker.rerank(retrievalQuery, fused);
        Set<String> seen = new HashSet<>();
        List<EvidenceItem> merged = new ArrayList<>(pinned.size() + reranked.size());
        for (EvidenceItem item : pinned) {
            if (seen.add(identityKey(item))) {
                merged.add(item);
            }
        }
        for (EvidenceItem item : reranked) {
            if (seen.add(identityKey(item))) {
                merged.add(item);
            }
        }
        List<EvidenceItem> evidence = merged.stream()
                .limit(evidenceLimit)
                .map(item -> item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE
                        ? item
                        : item.withTopicIds(matchedTopicIds(knowledge)))
                .toList();

        // 4.5 fail-open guard (09-27 adjudication direction (a)): the ask
        // named a complete, unambiguous paper-question identity and NOT ONE
        // validated anchor bound it (no bank row, no card, no content-store
        // QP/MS). Left as-is, generic retrieval answers anyway from
        // textually-similar wrong-paper chunks and the generator preserves
        // the ask's paper framing — the confident misattribution class
        // ("june 2019 paper 2 question 10" answered from Jan-2022-1C chunks).
        // Zero the pool: the deterministic refusal below fires with the
        // identity echoed and citations empty.
        boolean identityBoundUnserved = resolution.identityParsed() && pinned.isEmpty();
        if (identityBoundUnserved) {
            log.info("KA-RAG fail-open guard: identity [{}] bound no validated anchor; "
                    + "deterministic refusal", resolution.identityLabel());
            evidence = List.of();
        }

        // 5. grounding gate: no evidence → refuse, deterministically, no LLM
        TutorGenerator.GeneratedAnswer generated;
        boolean refused = evidence.isEmpty();
        ContextAssembler.TutorContext context = null;
        if (refused) {
            generated = identityBoundUnserved
                    ? new TutorGenerator.GeneratedAnswer(PAPER_IDENTITY_REFUSAL.formatted(
                            Objects.requireNonNullElse(resolution.identityLabel(),
                                    "that paper question")),
                            null, "deterministic-paper-refusal")
                    : new TutorGenerator.GeneratedAnswer(REFUSAL, null, "deterministic-refusal");
        } else {
            context = contextAssembler.assemble(knowledge, evidence, learnerId);
            generated = generator.generate(query, turns, context);
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
                latencyMs, Instant.now(), interventionType, turns.size(), sessionId));

        log.info("KA-RAG answered ({} evidence, {} topics, refused={}, {} history turn(s), {} ms)",
                evidence.size(), knowledge.topics().size(), refused, turns.size(),
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

    /**
     * Retrieval query for a conversational ask: the final question plus the
     * most recent turns' text, oldest material trimmed first so the question
     * and the immediately preceding exchange always survive. No labels — the
     * KG tokenizer and the embedding model both want plain content. With no
     * history this is the question verbatim (single-turn asks are unchanged).
     */
    static String retrievalQuery(String question, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        List<String> parts = new ArrayList<>(history.size() + 1);
        int used = question.length();
        int from = Math.max(0, history.size() - RETRIEVAL_WINDOW_TURNS);
        for (int i = history.size() - 1; i >= from; i--) {
            String text = history.get(i).text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (used + text.length() > RETRIEVAL_QUERY_MAX_CHARS && !parts.isEmpty()) {
                break;
            }
            parts.add(text);
            used += text.length();
            if (used >= RETRIEVAL_QUERY_MAX_CHARS) {
                break;
            }
        }
        // collected newest-first; render oldest-first with the question last
        StringBuilder sb = new StringBuilder(used + 1);
        for (int i = parts.size() - 1; i >= 0; i--) {
            sb.append(parts.get(i)).append(' ');
        }
        return sb.append(question).toString().strip();
    }

    /** Pipeline identity: a chunk by its row, a KG node by its node id. */
    private static String identityKey(EvidenceItem item) {
        return item.source() + "|"
                + (item.chunkId() != null ? item.chunkId() : "node:" + item.nodeId());
    }
}
