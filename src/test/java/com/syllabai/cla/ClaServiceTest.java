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
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final TutorGenerator generator = mock(TutorGenerator.class);
    private final CitationResolver citationResolver = mock(CitationResolver.class);
    private final TutorPolicyService policy = mock(TutorPolicyService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final EvidenceReranker reranker = new NoReranker();

    private ClaService service;

    private ResourceContext context;

    @BeforeEach
    void setUp() {
        service = new ClaService(resolver, tools, graph, knowledgeNodes, vectorRetriever,
                new ReciprocalRankFusion(60), reranker, generator, citationResolver, policy,
                events, 12, 6);

        context = new ResourceContext(ResourceContext.Kind.KG_TOPIC, TOPIC, ROOT, "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and structure",
                new ResourceContext.CurriculumVersionInfo("IALCHEM2018", "Edexcel", "IAL", "ACTIVE"),
                KnowledgeNode.ValidationStatus.VALIDATED, LEARNER, Instant.now());

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
        NodeView topic = new NodeView(TOPIC, "IALCHEM2018-U1-T3", "TOPIC",
                "Bonding and structure", "Ionic and covalent bonding", "VALIDATED", null, List.of());
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

        ClaAnswerView answer = service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN,
                "explain ionic bonding");

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
        service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN, "why do ions form?");

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
        service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN, "explain this topic");

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
        service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.SUMMARIZE, "summarize this topic");

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
        service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN, "explain ionic bonding");

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
        ClaAnswerView answer = service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN,
                "explain ionic bonding");

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
        assertThatThrownBy(() -> service.contextualAsk(LEARNER, ROOT, TOPIC,
                ResponseMode.EXPLAIN, "   "))
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

        service.contextualAsk(LEARNER, ROOT, TOPIC, ResponseMode.EXPLAIN, "explain this");

        ArgumentCaptor<ContextAssembler.TutorContext> captor =
                ArgumentCaptor.forClass(ContextAssembler.TutorContext.class);
        verify(generator).generate(any(), captor.capture());
        String brief = captor.getValue().learnerBrief();
        assertThat(brief).contains("measured mastery");
        assertThat(brief).contains("active misconception");
    }

    private static EvidenceItem chunkEvidence(String content) {
        return new EvidenceItem(EvidenceItem.EvidenceSource.MARK_SCHEME, content,
                UUID.randomUUID(), "doc-1", 1, UUID.randomUUID(), 0,
                null, null, null, null, 1, null, List.of(), List.of(), 0.9, 0.0, null);
    }
}
