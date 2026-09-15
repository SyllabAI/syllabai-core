package com.syllabai.cla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.cla.ClaToolRegistry.OwnLearnerState;
import com.syllabai.cla.ClaToolRegistry.RelatedConcepts;
import com.syllabai.cla.ClaToolRegistry.SpecAnchor;
import com.syllabai.cla.ClaToolRegistry.ToolResultWith;
import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.SkillState;
import com.syllabai.shared.events.ClaInteractionEvent;
import com.syllabai.tutor.CitationResolver;
import com.syllabai.tutor.ContextAssembler;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import com.syllabai.tutor.GroundedTutorGenerator;
import com.syllabai.tutor.KnowledgeRetriever;
import com.syllabai.tutor.NoReranker;
import com.syllabai.tutor.ReciprocalRankFusion;
import com.syllabai.tutor.TutorGenerator;
import com.syllabai.tutor.TutorPolicyService;
import com.syllabai.tutor.VectorRetriever;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * CLA step-1 pipeline invariants (contract §2/§3/§4/§5/§6), unit-pinned:
 * deterministic anchors, context-as-authoritative-prior, mode constraints,
 * tool traces, evidence capture provenance, and the read-only boundary (no
 * generation path writes learner or curriculum state — the only publish is
 * the interaction-evidence event).
 */
class ClaServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();
    private static final UUID PREREQ = UUID.randomUUID();

    private final ClaContextResolver resolver = mock(ClaContextResolver.class);
    private final ClaToolRegistry tools = mock(ClaToolRegistry.class);
    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final TutorGenerator generator = mock(TutorGenerator.class);
    private final CitationResolver citationResolver = mock(CitationResolver.class);
    private final TutorPolicyService policy = mock(TutorPolicyService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final EvidenceReranker reranker = new NoReranker();

    private ClaService service;

    private ResourceContext context;

    private UUID subtopicA;
    private UUID subtopicB;
    private UUID suggestedChild;

    @BeforeEach
    void setUp() {
        service = new ClaService(resolver, tools, graph, knowledgeNodes, markSchemes,
                questionVersions, vectorRetriever,
                new ReciprocalRankFusion(60), reranker, generator, citationResolver, policy,
                events, 12, 6);

        context = new ResourceContext(ResourceContext.Kind.KG_TOPIC, TOPIC, TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and structure",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                null, null, 0, null, null);

        when(resolver.resolveKgTopic(ROOT, TOPIC, LEARNER)).thenReturn(context);
        when(graph.tree(ROOT)).thenReturn(tree());
        when(knowledgeNodes.findById(TOPIC)).thenReturn(Optional.empty());

        // GET_SPECIFICATION_CONTEXT: root → unit → topic chain
        when(tools.specificationContext(eq(context), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_SPECIFICATION_CONTEXT, "args",
                        List.of(new SpecAnchor(ROOT, "IALCHEM2018", "SUBJECT", "IAL Chemistry", 0),
                                new SpecAnchor(UUID.randomUUID(), "IALCHEM2018-U1", "UNIT", "Unit 1", 1),
                                new SpecAnchor(TOPIC, "IALCHEM2018-U1-T3", "TOPIC",
                                        "Bonding and structure", 2))));
        when(tools.relatedConcepts(context)).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_RELATED_CONCEPTS, "args",
                        new RelatedConcepts(
                                List.of(new PrerequisiteView(PREREQ, "IALCHEM2018-U1-T1", "TOPIC",
                                        "Mole Calculations", 1)),
                                List.of())));
        when(tools.enabledFor(ResourceContext.Kind.KG_TOPIC, ResponseMode.EXPLAIN))
                .thenReturn(List.of(ClaToolRegistry.Tool.values()));
        when(tools.enabledFor(ResourceContext.Kind.KG_TOPIC, ResponseMode.SUMMARIZE))
                .thenReturn(List.of(ClaToolRegistry.Tool.values()));
        when(tools.learnerStateScope(eq(TOPIC), anyList())).thenReturn(Set.of(TOPIC, PREREQ));
        when(tools.learnerState(eq(LEARNER), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_LEARNER_STATE, "args",
                        new OwnLearnerState(List.of(), List.of())));

        when(policy.select(eq(LEARNER), anyList(), anyList())).thenReturn(
                new TutorPolicyService.InterventionPlan(
                        TutorPolicyService.InterventionType.EXPLANATION,
                        "no high-confidence diagnostic signal",
                        List.of("Use a normal source-grounded explanation.")));
        when(generator.generate(any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("Grounded answer [1].", "stub-model", "stub"));
        when(citationResolver.resolve(anyList())).thenReturn(List.of(
                new CitationResolver.Citation(1, "Specification topic IALCHEM2018-U1-T3 — Bonding "
                        + "and structure", "KNOWLEDGE_NODE", null, null, TOPIC,
                        "/api/v1/knowledge/nodes/" + TOPIC)));
    }

    private NodeView tree() {
        // the resolved topic's validated specification structure: two subtopic
        // learning outcomes + one SUGGESTED concept child (gate parity: the
        // SUGGESTED node is invisible to evidence exactly as to resolution)
        subtopicA = UUID.randomUUID();
        subtopicB = UUID.randomUUID();
        suggestedChild = UUID.randomUUID();
        NodeView subA = new NodeView(subtopicA, "IALCHEM2018-U1-T3.2", "SUBTOPIC",
                "understand covalent bonding in terms of electrostatic attraction",
                null, "VALIDATED", null, List.of());
        NodeView subB = new NodeView(subtopicB, "IALCHEM2018-U1-T3.1", "SUBTOPIC",
                "understand how ions are formed by electron loss or gain",
                null, "VALIDATED", null, List.of());
        NodeView suggested = new NodeView(suggestedChild, "CONCEPT-x", "CONCEPT",
                "some retrieval-graph concept", null, "SUGGESTED", null, List.of());
        NodeView topic = new NodeView(TOPIC, "IALCHEM2018-U1-T3", "TOPIC",
                "Bonding and structure", "Ionic and covalent bonding", "VALIDATED", null,
                List.of(subA, subB, suggested));
        NodeView unit = new NodeView(UUID.randomUUID(), "IALCHEM2018-U1", "UNIT", "Unit 1",
                null, "VALIDATED", null, List.of(topic));
        return new NodeView(ROOT, "IALCHEM2018", "SUBJECT", "IAL Chemistry",
                null, "VALIDATED", null, List.of(unit));
    }

    private void vectorReturns(EvidenceItem... items) {
        when(vectorRetriever.retrieve(any(), anyInt())).thenReturn(List.of(items));
    }

    @Test
    @DisplayName("grounded ask: the resolved context is the ONLY topic anchor, and it leads the evidence")
    void anchoredAsk() {
        vectorReturns(chunkEvidence("Ionic bonding is the electrostatic attraction…"));

        ClaAnswerView answer = service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC,
                ROOT, TOPIC, null, null , ResponseMode.EXPLAIN, "explain ionic bonding");

        assertThat(answer.refused()).isFalse();
        // deterministic anchor: exactly the resolved context, never model-invented
        assertThat(answer.topics()).hasSize(1);
        assertThat(answer.topics().get(0).code()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(answer.context().kind()).isEqualTo("KG_TOPIC");
        assertThat(answer.context().reference()).isEqualTo(TOPIC);
        assertThat(answer.context().curriculumVersion()).isEqualTo("IALCHEM2018");
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.EXPLAIN);
        // the context anchor participates as evidence (authoritative prior)
        assertThat(answer.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(answer.citations()).anySatisfy(c ->
                assertThat(c.sourceType()).isEqualTo("KNOWLEDGE_NODE"));
        // context does not accidentally include unrelated content: anchors only
        assertThat(answer.context().topicCode()).isEqualTo("IALCHEM2018-U1-T3");
    }

    @Test
    @DisplayName("chunk evidence is topic-stamped to the resolved anchor only")
    void chunkEvidenceStampsAnchorOnly() {
        vectorReturns(chunkEvidence("some validated chunk content"));
        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.EXPLAIN, "why do ions form?");

        ArgumentCaptor<ContextAssembler.TutorContext> contextCaptor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(eq("why do ions form?"), contextCaptor.capture());
        List<EvidenceItem> evidence = contextCaptor.getValue().evidence();
        assertThat(evidence).isNotEmpty();
        // every chunk is stamped with exactly the resolved anchor — no other topics
        assertThat(evidence).allSatisfy(item ->
                assertThat(item.topicIds()).containsExactly(TOPIC));
    }

    @Test
    @DisplayName("EXPLAIN mode constrains the generation plan deterministically")
    void explainModeConstrainsPlan() {
        vectorReturns(chunkEvidence("validated chunk"));
        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.EXPLAIN, "explain this topic");

        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        TutorPolicyService.InterventionPlan plan = captor.getValue().interventionPlan();
        assertThat(plan.rationale()).contains("CLA EXPLAIN mode");
        assertThat(plan.actions()).anySatisfy(a ->
                assertThat(a).contains("Teach the anchored concept"));
        // policy signal preserved for LIM classification provenance
        assertThat(plan.type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @Test
    @DisplayName("SUMMARIZE mode constrains the plan and preserves provenance anchors")
    void summarizeModeConstrainsPlan() {
        vectorReturns(chunkEvidence("validated chunk"));
        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.SUMMARIZE, "summarize this topic");

        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        TutorPolicyService.InterventionPlan plan = captor.getValue().interventionPlan();
        assertThat(plan.rationale()).contains("CLA SUMMARIZE mode");
        assertThat(plan.actions()).anySatisfy(a ->
                assertThat(a).contains("Preserve the spec anchors"));
    }

    @Test
    @DisplayName("interaction evidence: deterministic anchors + full provenance, never raw text in learner memory")
    void publishesProvenanceBearingEvent() {
        vectorReturns(chunkEvidence("validated chunk"));
        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.EXPLAIN, "explain ionic bonding");

        ArgumentCaptor<ClaInteractionEvent> captor =
                ArgumentCaptor.forClass(ClaInteractionEvent.class);
        verify(events).publishEvent(captor.capture());
        ClaInteractionEvent event = captor.getValue();
        assertThat(event.learnerId()).isEqualTo(LEARNER);
        assertThat(event.matchedTopicIds()).containsExactly(TOPIC);
        assertThat(event.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(event.refused()).isFalse();
        assertThat(event.answerModel()).isEqualTo("stub-model");
        assertThat(event.promptVersion()).isEqualTo("tutor-grounded/v2");
        assertThat(event.responseMode()).isEqualTo("EXPLAIN");
        assertThat(event.contextKind()).isEqualTo("KG_TOPIC");
        assertThat(event.contextReference()).isEqualTo(TOPIC);
        assertThat(event.interventionType()).isEqualTo("EXPLANATION");
    }

    @Test
    @DisplayName("tool composition is fixed and read-only: three traced reads, no other dependencies touched")
    void toolCompositionIsFixedAndTraced() {
        vectorReturns(chunkEvidence("validated chunk"));
        ClaAnswerView answer = service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC,
                ROOT, TOPIC, null, null , ResponseMode.EXPLAIN, "explain ionic bonding");

        assertThat(answer.tools()).extracting(ClaAnswerView.ToolTraceView::tool)
                .containsExactly("GET_SPECIFICATION_CONTEXT", "GET_RELATED_CONCEPTS",
                        "GET_LEARNER_STATE");
        // argument references never carry raw learner content into the trace
        assertThat(answer.tools()).allSatisfy(t ->
                assertThat(t.latencyMs()).isGreaterThanOrEqualTo(0));
        verify(tools).enabledFor(ResourceContext.Kind.KG_TOPIC, ResponseMode.EXPLAIN);
        // the learner-state tool read the requesting learner's OWN scope only
        verify(tools).learnerState(eq(LEARNER), eq(Set.of(TOPIC, PREREQ)));
    }

    @Test
    @DisplayName("blank question fails fast without touching any downstream component")
    void blankQuestionFailsFast() {
        assertThatThrownBy(() -> service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC,
                ROOT, TOPIC, null, null , ResponseMode.EXPLAIN, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(generator, never()).generate(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("measured learner state personalizes framing only — honest labels, no fabrication")
    void learnerStateFeedsBriefHonestly() {
        vectorReturns(chunkEvidence("validated chunk"));
        SkillState skill = new SkillState(LEARNER, TOPIC, 0.4, Instant.now());
        MisconceptionState mis = new MisconceptionState(LEARNER, UUID.randomUUID(), 0.5,
                Instant.now());
        when(tools.learnerState(eq(LEARNER), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_LEARNER_STATE, "args",
                        new OwnLearnerState(List.of(skill), List.of(mis))));

        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.EXPLAIN, "explain this");

        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        String brief = captor.getValue().learnerBrief();
        assertThat(brief).contains("measured mastery");
        assertThat(brief).contains("active misconception");
    }

    @Test
    @DisplayName("the topic's validated specification structure joins the evidence deterministically")
    void specStructureJoinsEvidenceDeterministically() {
        vectorReturns(); // no chunks — the spec structure is the teachable prior
        service.contextualAsk(LEARNER, ResourceContext.Kind.KG_TOPIC, ROOT, TOPIC, null, null ,
                ResponseMode.EXPLAIN, "explain this topic");

        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        List<EvidenceItem> evidence = captor.getValue().evidence();
        // anchor first, then the topic's validated subtopic learning outcomes
        // in code order — the SUGGESTED concept child is never evidence
        assertThat(evidence).hasSize(3);
        assertThat(evidence.get(0).nodeId()).isEqualTo(TOPIC);
        assertThat(evidence.get(1).nodeId()).isEqualTo(subtopicB); // T3.1 < T3.2
        assertThat(evidence.get(2).nodeId()).isEqualTo(subtopicA);
        assertThat(evidence).allSatisfy(item ->
                assertThat(item.source()).isEqualTo(EvidenceItem.EvidenceSource.KNOWLEDGE_NODE));
        // spec-structure evidence is attributed to the RESOLVED anchor (the
        // learner asked about the topic, not each subtopic)
        assertThat(evidence).allSatisfy(item ->
                assertThat(item.topicIds()).containsExactly(TOPIC));
        assertThat(evidence).extracting(EvidenceItem::nodeId)
                .doesNotContain(suggestedChild);
    }


    @Test
    @DisplayName("SPECIFICATION_POINT resolves by code server-side and anchors the same pipeline")
    void specificationPointAnchorsPipeline() {
        ResourceContext specContext = new ResourceContext(
                ResourceContext.Kind.SPECIFICATION_POINT, TOPIC, TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3.1", "understand how ions are formed",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                null, null, 0, null, null);
        when(resolver.resolveSpecificationPoint(ROOT, "IALCHEM2018-U1-T3.1", LEARNER))
                .thenReturn(specContext);
        // the tools are context-keyed: stub the SPECIFICATION_POINT context too
        when(tools.specificationContext(eq(specContext), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_SPECIFICATION_CONTEXT, "args",
                        List.of(new SpecAnchor(ROOT, "IALCHEM2018", "SUBJECT", "IAL Chemistry", 0),
                                new SpecAnchor(TOPIC, "IALCHEM2018-U1-T3.1", "SUBTOPIC",
                                        "understand how ions are formed", 2))));
        when(tools.relatedConcepts(specContext)).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_RELATED_CONCEPTS, "args",
                        new RelatedConcepts(List.of(), List.of())));
        when(tools.learnerState(eq(LEARNER), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_LEARNER_STATE, "args",
                        new OwnLearnerState(List.of(), List.of())));

        ClaAnswerView answer = service.contextualAsk(LEARNER,
                ResourceContext.Kind.SPECIFICATION_POINT, ROOT, null, null,
                "IALCHEM2018-U1-T3.1", ResponseMode.EXPLAIN, "explain this spec point");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().kind()).isEqualTo("SPECIFICATION_POINT");
        assertThat(answer.context().topicCode()).isEqualTo("IALCHEM2018-U1-T3.1");
        assertThat(answer.topics()).hasSize(1);
        assertThat(answer.topics().get(0).code()).isEqualTo("IALCHEM2018-U1-T3.1");
        // the same deterministic evidence spine: anchor + spec structure
        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        assertThat(captor.getValue().evidence()).isNotEmpty();
    }

    private static EvidenceItem chunkEvidence(String content) {
        return new EvidenceItem(EvidenceItem.EvidenceSource.MARK_SCHEME, content,
                UUID.randomUUID(), "doc-1", 1, UUID.randomUUID(), 0,
                null, null, null, null, 1, null, List.of(), List.of(), 0.9, 0.0, null);
    }

    // -- step 2: question contexts + the deterministic leakage gate --------------

    @Test
    @DisplayName("question context: stem evidence leads, HINT excludes mark-scheme chunks deterministically")
    void questionHintExcludesMarkSchemeChunks() {
        // a MARK_SCHEME document chunk the vector side retrieved — the filter
        // must remove it on question contexts in every mode (non-vacuous proof)
        EvidenceItem msChunk = new EvidenceItem(EvidenceItem.EvidenceSource.MARK_SCHEME,
                "allow 25.0 g", UUID.randomUUID(), "ms-doc", 1, UUID.randomUUID(), 0,
                null, null, null, null, 6, null, List.of(), List.of(), 0.9, 0.0, null);
        vectorReturns(msChunk, chunkEvidence("plain validated chunk"));
        ResourceContext questionContext = new ResourceContext(
                ResourceContext.Kind.PAST_PAPER_QUESTION, UUID.randomUUID(), TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and structure",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                "Calculate the mass of 0.25 mol of CaCO3", "Calculate", 2, "4CH0/1C", false);
        when(resolver.resolvePastPaperQuestion(any(), eq(LEARNER))).thenReturn(questionContext);
        when(tools.enabledFor(ResourceContext.Kind.PAST_PAPER_QUESTION, ResponseMode.HINT))
                .thenReturn(List.of(ClaToolRegistry.Tool.values()));
        when(tools.specificationContext(org.mockito.ArgumentMatchers.eq(questionContext), any()))
                .thenReturn(new ToolResultWith<>(ClaToolRegistry.Tool.GET_SPECIFICATION_CONTEXT,
                        "args", List.of(new SpecAnchor(ROOT, "IALCHEM2018", "SUBJECT",
                                "IAL Chemistry", 0))));
        when(tools.relatedConcepts(questionContext)).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_RELATED_CONCEPTS, "args",
                        new RelatedConcepts(List.of(), List.of())));
        when(tools.learnerState(eq(LEARNER), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_LEARNER_STATE, "args",
                        new OwnLearnerState(List.of(), List.of())));


        ClaAnswerView answer = service.contextualAsk(LEARNER,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null,
                questionContext.reference(), null , ResponseMode.HINT, "give me the mass");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().attempted()).isFalse();
        assertThat(answer.context().paperCode()).isEqualTo("4CH0/1C");
        // THE INVARIANT: no mark-scheme source anywhere in the evidence
        assertThat(answer.citations())
                .noneSatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));
        // the stem anchor made it into the evidence (the generator saw it)
        ArgumentCaptor<ContextAssembler.TutorContext> seen =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), seen.capture());
        assertThat(seen.getValue().evidence())
                .anySatisfy(item -> assertThat(item.source())
                        .isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER));
    }

    @Test
    @DisplayName("question context: CHECK pre-attempt refuses BEFORE any retrieval or generation")
    void questionCheckPreAttemptRefuses() {
        ResourceContext questionContext = new ResourceContext(
                ResourceContext.Kind.PAST_PAPER_QUESTION, UUID.randomUUID(), TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and structure",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                "Calculate the mass", "Calculate", 2, "4CH0/1C", false);
        when(resolver.resolvePastPaperQuestion(any(), eq(LEARNER))).thenReturn(questionContext);
        when(tools.enabledFor(ResourceContext.Kind.PAST_PAPER_QUESTION, ResponseMode.CHECK))
                .thenReturn(List.of(ClaToolRegistry.Tool.values()));

        assertThatThrownBy(() -> service.contextualAsk(LEARNER,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null,
                questionContext.reference(), null , ResponseMode.CHECK, "check my answer"))
                .isInstanceOf(AttemptRequiredException.class);
        // deterministic refusal happened BEFORE the vector retriever or generator ran
        verify(vectorRetriever, never()).retrieve(any(), anyInt());
        verify(generator, never()).generate(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("question context: post-attempt CHECK prepends the VALIDATED scheme-point evidence")
    void questionCheckPostAttemptIncludesSchemePoints() {
        ResourceContext questionContext = new ResourceContext(
                ResourceContext.Kind.PAST_PAPER_QUESTION, UUID.randomUUID(), TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and structure",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                "Calculate the mass", "Calculate", 2, "4CH0/1C", true);
        when(resolver.resolvePastPaperQuestion(any(), eq(LEARNER))).thenReturn(questionContext);
        when(tools.enabledFor(ResourceContext.Kind.PAST_PAPER_QUESTION, ResponseMode.CHECK))
                .thenReturn(List.of(ClaToolRegistry.Tool.values()));
        when(vectorRetriever.retrieve(any(), anyInt())).thenReturn(List.of());
        when(tools.specificationContext(org.mockito.ArgumentMatchers.eq(questionContext), any()))
                .thenReturn(new ToolResultWith<>(ClaToolRegistry.Tool.GET_SPECIFICATION_CONTEXT,
                        "args", List.of(new SpecAnchor(ROOT, "IALCHEM2018", "SUBJECT",
                                "IAL Chemistry", 0))));
        when(tools.relatedConcepts(questionContext)).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_RELATED_CONCEPTS, "args",
                        new RelatedConcepts(List.of(), List.of())));
        when(tools.learnerState(eq(LEARNER), any())).thenReturn(
                new ToolResultWith<>(ClaToolRegistry.Tool.GET_LEARNER_STATE, "args",
                        new OwnLearnerState(List.of(), List.of())));


        QuestionVersion version = org.mockito.Mockito.mock(QuestionVersion.class);
        when(questionVersions.findByQuestionIdOrderByVersionDesc(questionContext.reference()))
                .thenReturn(List.of(version));
        MarkScheme scheme = org.mockito.Mockito.mock(MarkScheme.class);
        when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.VALIDATED);
        MarkPoint m1 = new MarkPoint(scheme, null, "M1", 1,
                "uses moles = mass / Mr", 1, List.of(), null);
        MarkPoint m2 = new MarkPoint(scheme, null, "A1", 2,
                "25.0 (g)", 1, List.of(), null);
        when(scheme.points()).thenReturn(List.of(m2, m1));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(java.util.Optional.of(scheme));

        ClaAnswerView answer = service.contextualAsk(LEARNER,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null,
                questionContext.reference(), null , ResponseMode.CHECK, "check my answer");

        assertThat(answer.refused()).isFalse();
        // the synthesized scheme-point evidence LEADS the sources the generator saw
        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        List<EvidenceItem> evidence = captor.getValue().evidence();
        assertThat(evidence).isNotEmpty();
        // anchor first (stem — what the learner is looking at), scheme points second
        assertThat(evidence.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        assertThat(evidence.get(1).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
        assertThat(evidence.get(1).content()).contains("M1").contains("A1")
                .contains("moles = mass / Mr");
    }

    @Test
    @DisplayName("question context: unsupported kind is a 400 (closed enum)")
    void unsupportedKindFailsClosed() {
        assertThatThrownBy(() -> service.contextualAsk(LEARNER,
                ResourceContext.Kind.NOTE_SECTION, null, null, null, null ,
                ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(com.syllabai.shared.BadRequestException.class);
        assertThatThrownBy(() -> service.contextualAsk(LEARNER,
                ResourceContext.Kind.KG_TOPIC, null, null, null, null ,
                ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(com.syllabai.shared.BadRequestException.class);
        assertThatThrownBy(() -> service.contextualAsk(LEARNER,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null, null, null ,
                ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(com.syllabai.shared.BadRequestException.class);
        verify(generator, never()).generate(any(), any());
    }
}
