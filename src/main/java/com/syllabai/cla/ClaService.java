package com.syllabai.cla;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionPartRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.cla.ClaToolRegistry.RelatedConcepts;
import com.syllabai.cla.ClaToolRegistry.Tool;
import com.syllabai.cla.ClaToolRegistry.ToolResultWith;
import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
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
import java.util.Comparator;
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
 *   → hybrid retrieval: anchor KG node + its validated spec structure (the
 *     authoritative prior) + validated chunks
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

    /**
     * How many of the resolved topic's DIRECT VALIDATED subtopic learning
     * outcomes may join the deterministic evidence (§10.4 evaluation finding:
     * a bare topic title is honest but not teachable — the specification
     * structure beneath the topic is curriculum truth and carries the actual
     * learning outcomes). Bounded so the evidence cap still leaves room for
     * validated chunks when retrieval has content to offer.
     */
    static final int SPEC_STRUCTURE_LIMIT = 4;

    private final ClaContextResolver resolver;
    private final ClaToolRegistry tools;
    private final KnowledgeGraphService graph;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final SubjectRepository subjects;
    private final MarkSchemeRepository markSchemes;
    private final QuestionVersionRepository questionVersions;
    private final QuestionPartRepository questionParts;
    private final AttemptRepository attempts;
    private final AnswerRepository answers;
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
                      SubjectRepository subjects,
                      MarkSchemeRepository markSchemes,
                      QuestionVersionRepository questionVersions,
                      QuestionPartRepository questionParts,
                      AttemptRepository attempts,
                      AnswerRepository answers,
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
        this.subjects = subjects;
        this.markSchemes = markSchemes;
        this.questionVersions = questionVersions;
        this.questionParts = questionParts;
        this.attempts = attempts;
        this.answers = answers;
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
     * @param kind        the context kind (KG_TOPIC | SPECIFICATION_POINT |
     *                    PAST_PAPER_QUESTION | QUESTION_PART in this runtime;
     *                    anything else is a 400 — closed enum, §1)
     * @param rootId      opaque reference (KG_TOPIC/SPECIFICATION_POINT: required;
     *                    QUESTION_PART: optional subject-root scope check)
     * @param topicNodeId opaque reference (KG_TOPIC): the anchored topic
     * @param questionId  opaque reference (PAST_PAPER_QUESTION): the anchored question
     * @param partId      opaque reference (QUESTION_PART): the anchored part on the
     *                    question's CURRENT validated version
     * @param specCode    opaque reference (SPECIFICATION_POINT): the spec-point code the
     *                    learner is reading (e.g. "4CH1-1.18") — server resolves it
     * @param mode        explicit response mode (contract §3)
     * @param question    the learner's question within the anchored context
     */
    public ClaAnswerView contextualAsk(UUID learnerId, ResourceContext.Kind kind,
                                       UUID rootId, UUID topicNodeId, UUID questionId,
                                       UUID partId, String specCode, ResponseMode mode,
                                       String question) {
        if (learnerId == null) {
            throw new IllegalArgumentException("learnerId is required on the CLA surface");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        long startedAt = System.nanoTime();
        List<ClaToolRegistry.ToolTrace> toolTraces = new ArrayList<>();

        // 1. server-side context resolution — fail-closed (contract §1, §5)
        ResourceContext context = switch (kind) {
            case KG_TOPIC -> {
                if (rootId == null || topicNodeId == null) {
                    throw new BadRequestException(
                            "KG_TOPIC context requires rootId and topicNodeId");
                }
                yield resolver.resolveKgTopic(rootId, topicNodeId, learnerId);
            }
            case SPECIFICATION_POINT -> {
                if (rootId == null) {
                    throw new BadRequestException(
                            "SPECIFICATION_POINT context requires rootId");
                }
                yield resolver.resolveSpecificationPoint(rootId, specCode, learnerId);
            }
            case SMART_LESSON -> {
                if (rootId == null || topicNodeId == null) {
                    throw new BadRequestException(
                            "SMART_LESSON context requires rootId and topicNodeId");
                }
                yield resolver.resolveSmartLesson(rootId, topicNodeId, learnerId);
            }
            case PAST_PAPER_QUESTION -> {
                if (questionId == null) {
                    throw new BadRequestException(
                            "PAST_PAPER_QUESTION context requires questionId");
                }
                yield resolver.resolvePastPaperQuestion(questionId, learnerId);
            }
            case QUESTION_PART -> {
                if (partId == null) {
                    throw new BadRequestException(
                            "QUESTION_PART context requires partId");
                }
                yield resolver.resolveQuestionPart(partId, rootId, learnerId);
            }
            default -> throw new BadRequestException(
                    "context kind not supported by this runtime step: " + kind);
        };
        // the registry, not the caller, decides which tools may run (§4.1);
        // the leakage gate (§7.4) runs BEFORE any retrieval or generation
        tools.enabledFor(context.kind(), mode);
        ClaLeakagePolicy.checkModeAdmission(context, mode);

        // 2. bounded read-only tools, fixed composition (§4) — each invocation timed
        NodeView subjectTree = graph.tree(context.rootId());
        ToolResultWith<List<ClaToolRegistry.SpecAnchor>> specContext =
                trace(toolTraces, () -> tools.specificationContext(context, subjectTree));
        ToolResultWith<RelatedConcepts> related =
                trace(toolTraces, () -> tools.relatedConcepts(context));

        // 3. deterministic anchors: the resolved context is the ONLY topic match
        //    (curriculum-node kinds anchor the resolved node; question contexts
        //    anchor the question's primary topic)
        List<KnowledgeRetriever.KnowledgeContext.MatchedTopic> anchors = List.of(
                new KnowledgeRetriever.KnowledgeContext.MatchedTopic(
                        context.topicNodeId(), context.topicCode(), context.topicTitle(), 1.0));
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

        // 4. hybrid evidence: the anchor as authoritative prior, joined by the
        //    topic's own validated specification structure (direct subtopic
        //    learning outcomes — deterministic, resolved from the same subject
        //    tree, never retrieval, never model-invented) + validated chunks
        List<EvidenceItem> kgCandidates = new ArrayList<>();
        anchors.stream().map(topic -> EvidenceItem.fromNode(topic.nodeId(), topic.code(),
                        "TOPIC", topic.title(), nodeDescription(context), topic.matchScore()))
                .forEach(kgCandidates::add);
        kgCandidates.addAll(specStructureEvidence(context, subjectTree));
        List<EvidenceItem> vectorList = vectorRetriever.retrieve(question, vectorCandidates,
                scopeOf(context));
        // plan §7 per-kind weights on the serving fusion (same posture as
        // KaRagService): KG anchors weigh 1.0, chunk kinds scale per plan §7
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(List.of(kgCandidates, vectorList));
        List<EvidenceItem> evidence = reranker.rerank(question, fused)
                .stream()
                .limit(evidenceLimit)
                .map(item -> item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE
                        ? item
                        : item.withTopicIds(List.of(context.topicNodeId())))
                // §7.4 deterministic evidence filter (mark-scheme chunks on
                // question contexts), applied post-fusion, pre-gate
                .filter(item -> ClaLeakagePolicy.evidenceEligible(context, mode, item))
                .toList();

        // question contexts lead with FIXED deterministic evidence outside the
        // fusion pool (fusion keys on node/chunk identity; the anchored stem and
        // the question's own scheme points are id-anchored, not similarity-anchored):
        //   [stem (always — what the learner is looking at)]
        //   [+ the learner's OWN submitted answers (§7.3 CHECK feedback: the
        //    mode's stated job is to review the learner's submitted work —
        //    part-scoped on QUESTION_PART, latest attempt, resolved by ids)]
        //   [+ VALIDATED scheme points (§7.3, post-attempt, never HINT)]
        // then the bounded cap applies to the whole list
        if (context.isQuestionContext()) {
            List<EvidenceItem> lead = new ArrayList<>();
            lead.add(questionStemEvidence(context));
            if (ClaLeakagePolicy.schemePointEvidenceAllowed(context, mode)) {
                EvidenceItem workEvidence = learnerWorkEvidence(context);
                if (workEvidence != null) {
                    lead.add(workEvidence);
                }
                EvidenceItem schemeEvidence = schemePointEvidence(context);
                if (schemeEvidence != null) {
                    lead.add(schemeEvidence);
                }
            }
            lead.addAll(evidence);
            evidence = List.copyOf(lead.subList(0, Math.min(lead.size(), evidenceLimit)));
        }

        // 5. grounding gate → mode-constrained grounded generation (Tutor stack)
        TutorGenerator.GeneratedAnswer generated;
        boolean refused = evidence.isEmpty();
        String interventionType = null;
        if (refused) {
            generated = new TutorGenerator.GeneratedAnswer(REFUSAL, null, "deterministic-refusal");
        } else {
            var scope = tools.learnerStateScope(context.topicNodeId(), related.value().prerequisites());
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
                learnerId, question.strip(), List.of(context.topicNodeId()), evidence.size(),
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
        String anchored = context.partLabel() != null
                ? "anchored question part (" + context.partLabel() + ") on topic "
                        + context.topicCode()
                : context.isQuestionContext()
                        ? "anchored question on topic " + context.topicCode()
                        : context.kind() == ResourceContext.Kind.SMART_LESSON
                                ? "anchored Smart Lesson on topic " + context.topicCode()
                                : "anchored topic " + context.topicCode();
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
                            "Cover the WHOLE anchored specification structure: every "
                                    + "provided specification statement appears in the "
                                    + "summary — never compress only the first source.",
                            "One concise clause per specification statement — compress, "
                                    + "do not expand (large topics must stay inside the "
                                    + "generation budget).",
                            "Preserve the spec anchors (topic code and source citations "
                                    + "for every statement group you cover).",
                            "Do not add material that is not present in the SOURCES."));
            case HINT -> new TutorPolicyService.InterventionPlan(
                    policyPlan.type(),
                    "CLA HINT mode on the " + anchored + " — scaffolding only "
                            + "(answer-leakage gate: no final answers, no mark-scheme points)",
                    List.of("Scaffold the learner's OWN next step: questions, cues and worked "
                            + "analogies from the SOURCES.",
                            "Never state the final answer; never enumerate mark-scheme points.",
                            "Stay within the anchored topic and its prerequisites."));
            case CHECK -> new TutorPolicyService.InterventionPlan(
                    policyPlan.type(),
                    "CLA CHECK mode post-attempt on the " + anchored
                            + " — full feedback unlocked by the attempt-state gate",
                    List.of("Review the learner's submitted answers (in the SOURCES as the "
                            + "learner-work entries) against the numbered SOURCES.",
                            "Walk through the mark-scheme points where they apply, citing [n].",
                            "Be specific about what earned marks and what did not, without "
                                    + "revealing internal probabilities or diagnostic rules."));
        };
    }

    /** honest learner brief from the GET_LEARNER_STATE tool result (§2.3) */
    private String learnerBrief(ClaToolRegistry.OwnLearnerState state, ResourceContext context) {
        String lessonLine = lessonActionBrief(context);
        String none = "Learner state: no prior measured evidence on " + context.topicCode() + ".";
        if (state.isEmpty()) {
            return lessonLine == null ? none : none + "\n" + lessonLine;
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
            return lessonLine == null ? none : none + "\n" + lessonLine;
        }
        if (lessonLine != null) {
            sb.append(lessonLine);
        }
        return sb.toString().strip();
    }

    /**
     * SMART_LESSON framing line (§2.3): the learner's OWN deterministic Smart
     * Lesson decision — honest labels only (action + audit reason + the
     * ladder's own reason detail), personalizes FRAMING and selection, never
     * quoted back as fact and never treated as curriculum truth. The action's
     * redirect target (a prerequisite/corrective node) is named so the
     * response can honestly acknowledge where the lesson is steering.
     */
    private String lessonActionBrief(ResourceContext context) {
        if (context.kind() != ResourceContext.Kind.SMART_LESSON
                || context.lessonAction() == null) {
            return null;
        }
        ResourceContext.LessonActionInfo a = context.lessonAction();
        StringBuilder sb = new StringBuilder("- the learner's deterministic Smart Lesson next "
                + "action on this topic: ").append(a.actionType())
                .append(" (").append(a.reasonCode()).append(')');
        if (a.targetCode() != null && !a.targetCode().equals(context.topicCode())) {
            sb.append(" — the ladder redirects to ").append(a.targetCode());
        }
        if (a.reasonDetail() != null && !a.reasonDetail().isBlank()) {
            sb.append(": ").append(a.reasonDetail());
        }
        return sb.toString();
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

    /**
     * The curriculum scope of a resolved CLA context (T-C07): the CLA house rule
     * is that curriculum identity comes from the OWNING SUBJECT of the anchored
     * resource (ClaContextResolver), so the scope is derived from that subject's
     * version + subject-root subtree — never from the global single-owner
     * resolution the free-text tutor uses. A resolved context whose subject
     * root cannot be re-resolved is a server defect and fails closed here
     * (same NotFound shape the resolver itself uses).
     */
    private CurriculumScope scopeOf(ResourceContext context) {
        Subject subject = subjects.findByKnowledgeNodeId(context.rootId())
                .orElseThrow(() -> new NotFoundException("curriculum subject root", context.rootId()));
        CurriculumVersion version = subject.curriculumVersion();
        return new CurriculumScope(version.id(), version.code(),
                new java.util.HashSet<>(knowledgeNodes.findSubtreeIds(context.rootId())));
    }

    private String nodeDescription(ResourceContext context) {
        return knowledgeNodes.findById(context.topicNodeId())
                .map(node -> node.description())
                .orElse(null);
    }

    /**
     * Deterministic specification-structure evidence (contract §5: the context
     * is the authoritative prior): the resolved topic's DIRECT VALIDATED
     * subtopic learning outcomes, code-ordered, bounded by
     * {@link #SPEC_STRUCTURE_LIMIT}. These are curriculum truth resolved from
     * the same subject tree the context was resolved from — no retrieval, no
     * model input, provenance-bearing (each item cites its own node id).
     * SUGGESTED/UNVALIDATED nodes are invisible here exactly as they are to
     * context resolution (§1.2 gate parity).
     */
    private List<EvidenceItem> specStructureEvidence(ResourceContext context,
                                                     NodeView subjectTree) {
        NodeView topic = findNode(subjectTree, context.topicNodeId());
        if (topic == null || topic.children() == null || topic.children().isEmpty()) {
            return List.of();
        }
        return topic.children().stream()
                .filter(c -> "SUBTOPIC".equals(c.type()))
                .filter(c -> "VALIDATED".equals(c.validationStatus()))
                .sorted(Comparator.comparing(NodeView::code,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(SPEC_STRUCTURE_LIMIT)
                .map(c -> EvidenceItem.fromNode(c.id(), c.code(), c.type(),
                        c.title(), c.description(), 0.9)
                        // attribution stays on the RESOLVED anchor: the learner
                        // asked about the topic, not each subtopic (LIM topic
                        // attribution, telemetry and NBA all key on this)
                        .withTopicIds(List.of(context.topicNodeId())))
                .toList();
    }

    private NodeView findNode(NodeView node, UUID id) {
        if (node == null) {
            return null;
        }
        if (id.equals(node.id())) {
            return node;
        }
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                NodeView found = findNode(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * §7.3 CHECK feedback input: the learner's OWN submitted answers from their
     * most recent attempt on the anchored question (their own data — the mode's
     * stated job is reviewing the learner's submitted work, which it cannot do
     * without seeing it). Part-level contexts receive ONLY the anchored part's
     * answer (part-scoped discipline). Resolved by ids, never model-selected;
     * null when no attempt answers exist. Uses the same admission gate as the
     * scheme points (post-attempt, never HINT).
     */
    private EvidenceItem learnerWorkEvidence(ResourceContext context) {
        UUID questionId = context.isQuestionPartContext()
                ? questionParts.findQuestionIdByPartId(context.reference()).orElse(null)
                : context.reference();
        if (questionId == null) {
            return null;
        }
        return attempts.findFirstByLearnerIdAndQuestionIdOrderByCreatedAtDesc(
                        context.learnerId(), questionId)
                .map(attempt -> {
                    String content = answers.findByAttemptIdOrderByQuestionPartId(attempt.id())
                            .stream()
                            .filter(a -> partAllowsAnswer(context, a))
                            .map(a -> "your submitted answer for part ("
                                    + answerPartLabel(a) + "): " + a.answerText())
                            .reduce((x, y) -> x + "\n" + y)
                            .orElse("");
                    if (content.isBlank()) {
                        return null;
                    }
                    return new EvidenceItem(EvidenceItem.EvidenceSource.LEARNER_WORK,
                            "The learner's submitted work (most recent attempt): " + content,
                            null, null, null, null, null, null, null, null, null,
                            null, null, List.of(), List.of(context.topicNodeId()),
                            1.0, 0.0, null);
                })
                .orElse(null);
    }

    /** part-scoped learner-work selection: a part context sees only ITS answer */
    static boolean partAllowsAnswer(ResourceContext context, com.syllabai.assessment.Answer answer) {
        if (!context.isQuestionPartContext()) {
            return true;
        }
        return context.reference().equals(answer.questionPartId());
    }

    /** proxy-safe part label for the learner-work evidence line */
    private String answerPartLabel(com.syllabai.assessment.Answer answer) {
        return questionParts.findById(answer.questionPartId())
                .map(com.syllabai.assessment.QuestionPart::label)
                .orElse("?");
    }

    /**
     * The anchored question's own stem as lead evidence (§2.1: the learner is
     * already looking at it — presenting it back is not a leak; it is the
     * anchor). Part-level contexts anchor the PART prompt they are looking at
     * (explicitly labeled). Provenance: the served stem/prompt of the
     * VALIDATED current version.
     */
    private EvidenceItem questionStemEvidence(ResourceContext context) {
        String command = context.questionCommandWord() == null ? ""
                : context.questionCommandWord() + " — ";
        String anchored = context.partLabel() != null
                ? "Part (" + context.partLabel() + ") (" + context.questionMarks()
                        + " marks) " + command + context.questionStem()
                : "Question (" + context.questionMarks() + " marks) " + command
                        + context.questionStem();
        return new EvidenceItem(EvidenceItem.EvidenceSource.QUESTION_PAPER, anchored,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(context.topicNodeId()), 1.0, 0.0, null);
    }

    /**
     * §7.3 post-attempt feedback evidence: the question's OWN VALIDATED
     * mark-scheme points from the assessment model (question-granular,
     * resolved by ids) — NOT page-level document chunks, which cannot be
     * bound to one question deterministically. Null when no VALIDATED scheme
     * exists (honest — feedback then grounds on spec context alone).
     *
     * <p>Part-level contexts (QUESTION_PART) receive the PART-APPROPRIATE
     * subset: points targeting the anchored part, plus whole-question points
     * (part null). A sibling part's points are not this part's marking
     * evidence and do not enter the SOURCES.</p>
     */
    private EvidenceItem schemePointEvidence(ResourceContext context) {
        // the scheme lookup keys on the question id: question-level contexts
        // carry it as the reference; part-level contexts resolve it through the
        // canonical part → version → question FK via a SCALAR projection (the
        // service is non-transactional with OSIV off — an entity traversal
        // would LazyInitializationException; see QuestionPartRepository)
        UUID questionId = context.isQuestionPartContext()
                ? questionParts.findQuestionIdByPartId(context.reference()).orElse(null)
                : context.reference();
        if (questionId == null) {
            return null;
        }
        return questionVersions.findByQuestionIdOrderByVersionDesc(questionId)
                .stream().findFirst()
                .flatMap(v -> markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(v.id()))
                .filter(s -> s.validationState() == MarkScheme.ValidationState.VALIDATED)
                .map(s -> {
                    String content = s.points().stream()
                            .filter(p -> partAllowsPoint(context, p))
                            .sorted(java.util.Comparator.comparingInt(MarkPoint::ordering))
                            .map(p -> p.ref() + " (" + p.marks() + "): " + p.text())
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("");
                    if (content.isBlank()) {
                        return null;
                    }
                    return new EvidenceItem(EvidenceItem.EvidenceSource.MARK_SCHEME,
                            "Mark scheme points: " + content,
                            null, null, null, null, null, null, null, null, null,
                            null, null, List.of(), List.of(context.topicNodeId()),
                            1.0, 0.0, null);
                })
                .orElse(null);
    }

    /**
     * Part-appropriate marking-evidence selection (§7.3): a part context
     * admits points targeting THAT part plus question-level points (part
     * null); question-level contexts admit all the question's points.
     */
    static boolean partAllowsPoint(ResourceContext context, MarkPoint point) {
        if (!context.isQuestionPartContext()) {
            return true;
        }
        return point.questionPartId() == null
                || context.reference().equals(point.questionPartId());
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
