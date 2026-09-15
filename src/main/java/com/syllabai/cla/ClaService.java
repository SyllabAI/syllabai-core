package com.syllabai.cla;

import com.syllabai.cla.ClaToolRegistry.RelatedConcepts;
import com.syllabai.cla.ClaToolRegistry.Tool;
import com.syllabai.cla.ClaToolRegistry.ToolResultWith;
import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.shared.events.ClaInteractionEvent;
import com.syllabai.tutor.CitationResolver;
import com.syllabai.tutor.ContextAssembler;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import com.syllabai.tutor.GroundedTutorGenerator;
import com.syllabai.tutor.KnowledgeRetriever;
import com.syllabai.tutor.ReciprocalRankFusion;
import com.syllabai.tutor.TutorGenerator;
import com.syllabai.tutor.TutorPolicyService;
import com.syllabai.tutor.VectorRetriever;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Contextual Learning Assistant orchestration — step-1 slice (CLA contract
 * §10.1 + §6). The pipeline, end to end:
 *
 * <pre>
 * ResourceContext (server-resolved, fail-closed)
 *   → bounded read-only tools (fixed composition, traced)
 *   → deterministic topic anchors (the resolved context — never model-invented)
 *   → hybrid retrieval: anchor KG node (authoritative prior) + validated chunks
 *   → reciprocal-rank fusion → rerank → evidence cap
 *   → grounding gate → mode-constrained grounded generation (Tutor stack)
 *   → citation resolution (same validation stack as the Tutor)
 *   → ClaInteractionEvent → provenance-bearing LIM rows (surface=CONTEXTUAL_ASSISTANT)
 * </pre>
 *
 * <p>Hard boundaries, all inherited unchanged:</p>
 * <ul>
 *   <li>the context anchor is ALWAYS evidence[0]-class prior — it is a
 *       validated curriculum node resolved server-side, so an anchored ask
 *       structurally cannot fabricate its topic;</li>
 *   <li>with zero surviving evidence the service refuses deterministically
 *       (no LLM call) — for KG_TOPIC step 1 this is structurally unreachable
 *       (the validated anchor itself is evidence), which is stated honestly
 *       here and pinned by tests; chunk-anchored context kinds in step 2
 *       exercise the refusal path against thin retrieval;</li>
 *   <li>the learner's measured state personalizes FRAMING only (contract
 *       §2.3) — honest labels, no internal probabilities disclosed;</li>
 *   <li>no step of this pipeline writes canonical KG, mastery, misconception
 *       or validation state — the only write is the LIM engagement append
 *       performed by the event recorder (the same governed path as the
 *       Tutor).</li>
 * </ul>
 */
@Service
public class ClaService {

    private static final Logger log = LoggerFactory.getLogger(ClaService.class);

    static final String REFUSAL = """
            I can't explain that from the validated course material anchored to this
            topic yet — there is no matching source content to ground an answer, and
            SyllabAI never guesses. Try rephrasing within the topic, or ask your
            teacher to ingest the relevant material.""";

    private final ClaContextResolver resolver;
    private final ClaToolRegistry tools;
    private final KnowledgeGraphService graph;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final VectorRetriever vectorRetriever;
    private final ReciprocalRankFusion fusion;
    private final EvidenceReranker reranker;
    private final TutorGenerator generator;
    private final CitationResolver citationResolver;
    private final TutorPolicyService policy;
    private final ApplicationEventPublisher events;

    private final int vectorCandidates;
    private final int evidenceLimit;

    public ClaService(ClaContextResolver resolver,
                      ClaToolRegistry tools,
                      KnowledgeGraphService graph,
                      KnowledgeNodeRepository knowledgeNodes,
                      VectorRetriever vectorRetriever,
                      ReciprocalRankFusion fusion,
                      EvidenceReranker reranker,
                      TutorGenerator generator,
                      CitationResolver citationResolver,
                      TutorPolicyService policy,
                      ApplicationEventPublisher events,
                      @Value("${syllabai.cla.vector-candidates:12}") int vectorCandidates,
                      @Value("${syllabai.cla.evidence-limit:6}") int evidenceLimit) {
        this.resolver = resolver;
        this.tools = tools;
        this.graph = graph;
        this.knowledgeNodes = knowledgeNodes;
        this.vectorRetriever = vectorRetriever;
        this.fusion = fusion;
        this.reranker = reranker;
        this.generator = generator;
        this.citationResolver = citationResolver;
        this.policy = policy;
        this.events = events;
        this.vectorCandidates = Math.max(1, vectorCandidates);
        this.evidenceLimit = Math.max(1, evidenceLimit);
    }

    /**
     * @param learnerId   the authenticated learner (learner surface — required)
     * @param rootId      opaque reference: the subject KG root being viewed
     * @param topicNodeId opaque reference: the anchored curriculum topic
     * @param mode        explicit response mode (contract §3)
     * @param question    the learner's question within the anchored context
     */
    public ClaAnswerView contextualAsk(UUID learnerId, UUID rootId, UUID topicNodeId,
                                       ResponseMode mode, String question) {
        if (learnerId == null) {
            throw new IllegalArgumentException("learnerId is required on the CLA surface");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        long startedAt = System.nanoTime();
        List<ClaToolRegistry.ToolTrace> toolTraces = new ArrayList<>();

        // 1. server-side context resolution — fail-closed (contract §1, §5)
        ResourceContext context = resolver.resolveKgTopic(rootId, topicNodeId, learnerId);
        // the registry, not the caller, decides which tools may run (§4.1)
        tools.enabledFor(context.kind(), mode);

        // 2. bounded read-only tools, fixed composition (§4) — each invocation timed
        NodeView subjectTree = graph.tree(rootId);
        ToolResultWith<List<ClaToolRegistry.SpecAnchor>> specContext =
                trace(toolTraces, () -> tools.specificationContext(context, subjectTree));
        ToolResultWith<RelatedConcepts> related =
                trace(toolTraces, () -> tools.relatedConcepts(context));

        // 3. deterministic anchors: the resolved context is the ONLY topic match
        List<KnowledgeRetriever.KnowledgeContext.MatchedTopic> anchors = List.of(
                new KnowledgeRetriever.KnowledgeContext.MatchedTopic(
                        context.reference(), context.topicCode(), context.topicTitle(), 1.0));
        List<KnowledgeRetriever.KnowledgeContext.PrerequisiteLink> prerequisiteLinks =
                related.value().prerequisites().stream()
                        .map(p -> new KnowledgeRetriever.KnowledgeContext.PrerequisiteLink(
                                context.reference(), p.id(), p.title(), p.depth()))
                        .toList();
        List<KnowledgeRetriever.KnowledgeContext.MisconceptionSignal> misconceptionSignals =
                related.value().misconceptions().stream()
                        .map(m -> new KnowledgeRetriever.KnowledgeContext.MisconceptionSignal(
                                context.reference(), m.nodeId(), m.title()))
                        .toList();
        KnowledgeRetriever.KnowledgeContext knowledge =
                new KnowledgeRetriever.KnowledgeContext(
                        anchors, prerequisiteLinks, misconceptionSignals);

        // 4. hybrid evidence: the anchor as authoritative prior + validated chunks
        List<EvidenceItem> kgCandidates = anchors.stream()
                .map(topic -> EvidenceItem.fromNode(topic.nodeId(), topic.code(), "TOPIC",
                        topic.title(), nodeDescription(context), topic.matchScore()))
                .toList();
        List<EvidenceItem> vectorList = vectorRetriever.retrieve(question, vectorCandidates);
        List<EvidenceItem> fused = fusion.fuse(List.of(kgCandidates, vectorList));
        List<EvidenceItem> evidence = reranker.rerank(question, fused)
                .stream()
                .limit(evidenceLimit)
                .map(item -> item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE
                        ? item
                        : item.withTopicIds(List.of(context.reference())))
                .toList();

        // 5. grounding gate → mode-constrained grounded generation (Tutor stack)
        TutorGenerator.GeneratedAnswer generated;
        boolean refused = evidence.isEmpty();
        String interventionType = null;
        if (refused) {
            generated = new TutorGenerator.GeneratedAnswer(REFUSAL, null, "deterministic-refusal");
        } else {
            var scope = tools.learnerStateScope(context.reference(), related.value().prerequisites());
            ToolResultWith<ClaToolRegistry.OwnLearnerState> ownState =
                    trace(toolTraces, () -> tools.learnerState(learnerId, scope));
            TutorPolicyService.InterventionPlan policyPlan =
                    policy.select(learnerId, knowledge.topics(), knowledge.misconceptions());
            TutorPolicyService.InterventionPlan modePlan =
                    modePlan(mode, context, policyPlan);
            interventionType = modePlan.type().name();
            generated = generator.generate(question.strip(), new ContextAssembler.TutorContext(
                    learnerBrief(ownState.value(), context),
                    knowledgeBrief(specContext.value(), knowledge),
                    evidence,
                    modePlan));
        }

        // 6. citations — the SAME resolution/validation stack as the Tutor
        List<CitationResolver.Citation> citations = citationResolver.resolve(evidence);
        double latencyMs = (System.nanoTime() - startedAt) / 1_000_000.0;

        // 7. interaction evidence (contract §6): deterministic anchors + provenance
        events.publishEvent(new ClaInteractionEvent(
                learnerId, question.strip(), List.of(context.reference()), evidence.size(),
                evidence.stream().map(item -> item.source().name()).toList(),
                refused, generated.model(), GroundedTutorGenerator.promptIdentity(),
                latencyMs, Instant.now(), interventionType,
                mode.name(), context.kind().name(), context.reference(),
                toolTraces.stream()
                        .map(t -> new ClaInteractionEvent.ToolInvocation(
                                t.tool(), t.args(), t.resultSize(), t.latencyMs()))
                        .toList()));

        log.info("CLA answered ({} evidence, anchored {}, mode={}, refused={}, {} ms)",
                evidence.size(), context.topicCode(), mode, refused,
                String.format(Locale.ROOT, "%.1f", latencyMs));

        return ClaAnswerView.of(generated.answer(), citations, context, mode,
                evidence.size(), generated.model(), generated.provider(), refused,
                latencyMs, toolTraces);
    }

    /** deterministic mode constraint (contract §3): the mode rewrites the plan */
    static TutorPolicyService.InterventionPlan modePlan(ResponseMode mode,
                                                        ResourceContext context,
                                                        TutorPolicyService.InterventionPlan policyPlan) {
        String anchored = "anchored topic " + context.topicCode();
        return switch (mode) {
            case EXPLAIN -> new TutorPolicyService.InterventionPlan(
                    policyPlan.type(),
                    "CLA EXPLAIN mode on the " + anchored + " — " + policyPlan.rationale(),
                    List.of("Teach the anchored concept from the numbered SOURCES, citing [n] where used.",
                            "Stay within the anchored topic and its prerequisites.",
                            "Use the learner brief to choose framing; never reveal internal "
                                    + "probabilities, model names or diagnostic rules."));
            case SUMMARIZE -> new TutorPolicyService.InterventionPlan(
                    policyPlan.type(),
                    "CLA SUMMARIZE mode on the " + anchored + " — " + policyPlan.rationale(),
                    List.of("Summarize the anchored topic from the numbered SOURCES only.",
                            "Preserve the spec anchors (topic code and source citations).",
                            "Do not add material that is not present in the SOURCES."));
        };
    }

    /** honest learner brief from the GET_LEARNER_STATE tool result (§2.3) */
    private String learnerBrief(ClaToolRegistry.OwnLearnerState state, ResourceContext context) {
        if (state.isEmpty()) {
            return "Learner state: no prior measured evidence on " + context.topicCode() + ".";
        }
        StringBuilder sb = new StringBuilder("Learner state for the anchored topic:\n");
        state.skills().stream()
                .filter(s -> s.nodeId().equals(context.reference()))
                .findFirst()
                .ifPresent(s -> sb.append("- measured mastery of '").append(context.topicTitle())
                        .append("': ").append(String.format(Locale.ROOT, "%.2f", s.mastery())).append('\n'));
        state.misconceptions().stream()
                .filter(m -> m.probability() >= 0.5)
                .forEach(m -> sb.append("- active misconception flagged on this topic (instructional "
                        + "strategy selected from evidence)\n"));
        if (sb.indexOf("- ") < 0) {
            return "Learner state: no prior measured evidence on " + context.topicCode() + ".";
        }
        return sb.toString().strip();
    }

    /** knowledge brief: spec chain + prerequisites + misconceptions */
    private String knowledgeBrief(List<ClaToolRegistry.SpecAnchor> specChain,
                                  KnowledgeRetriever.KnowledgeContext knowledge) {
        StringBuilder sb = new StringBuilder("Curriculum context:\n");
        knowledge.topics().forEach(t -> sb.append("- anchored topic ").append(t.code())
                .append(": ").append(t.title()).append('\n'));
        if (specChain.size() > 1) {
            sb.append("Specification chain of the anchored topic:\n");
            specChain.stream().limit(specChain.size() - 1)
                    .forEach(a -> sb.append("- ").append(a.code()).append(": ").append(a.title())
                            .append(" (").append(a.type()).append(")\n"));
        }
        if (!knowledge.prerequisites().isEmpty()) {
            sb.append("Prerequisites of the anchored topic:\n");
            knowledge.prerequisites().stream().limit(8)
                    .forEach(p -> sb.append("- ").append(p.title())
                            .append(" (").append(p.depth()).append(p.depth() == 1 ? " hop" : " hops deep")
                            .append(")\n"));
        }
        if (!knowledge.misconceptions().isEmpty()) {
            sb.append("Known misconceptions attached to this topic:\n");
            knowledge.misconceptions().stream().limit(6)
                    .forEach(m -> sb.append("- ").append(m.title()).append('\n'));
        }
        return sb.toString().strip();
    }

    private String nodeDescription(ResourceContext context) {
        return knowledgeNodes.findById(context.reference())
                .map(node -> node.description())
                .orElse(null);
    }

    private <T> ToolResultWith<T> trace(List<ClaToolRegistry.ToolTrace> traces,
                                        java.util.function.Supplier<ToolResultWith<T>> execution) {
        long start = System.nanoTime();
        ToolResultWith<T> result = execution.get();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        traces.add(new ClaToolRegistry.ToolTrace(result.tool().name(), result.args(),
                result.resultSize(), latencyMs));
        return result;
    }
}
