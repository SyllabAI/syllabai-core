package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.shared.events.TutorAnsweredEvent;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MatchedTopic;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * KA-RAG orchestration (T-024): the full pipeline composes scope resolution →
 * intent → hybrid retrieval → fusion → rerank → assembly → generation →
 * citations, the grounding gate refuses deterministically on empty evidence
 * (no LLM call), and every outcome publishes the research event.
 *
 * <p>T-C07: the curriculum scope is resolved once per ask and handed to BOTH
 * retrieval surfaces; an unresolved scope means both surfaces stay empty —
 * the deterministic refusal, with the research event still published
 * (fail-closed, never serve unscoped or cross-curriculum).</p>
 */
class KaRagServiceTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.fromString("00000000-0000-0000-0000-0000000004c1"), "4CH1-2017",
            Set.of(UUID.randomUUID()));

    private final KnowledgeRetriever knowledgeRetriever = mock(KnowledgeRetriever.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final PaperQuestionResolver paperQuestionResolver = mock(PaperQuestionResolver.class);
    private final CurriculumScopeResolver curriculumScopes = mock(CurriculumScopeResolver.class);
    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);
    private final EvidenceReranker reranker = new NoReranker();
    private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
    private final TutorGenerator generator = mock(TutorGenerator.class);
    private final CitationResolver citationResolver = new SimpleCitationResolver();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final KaRagService service = new KaRagService(knowledgeRetriever, vectorRetriever,
            paperQuestionResolver, curriculumScopes, fusion, reranker, contextAssembler, generator,
            citationResolver, events, 5, 12, 6);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID topicId = UUID.randomUUID();

    /** The resolver mock default: not a paper ask (the pre-guard pipeline shape). */
    private void resolverReturns(PaperQuestionResolver.Resolution resolution) {
        when(paperQuestionResolver.resolveWithVerdict(anyString(), any(CurriculumScope.class)))
                .thenReturn(resolution);
    }

    @Test
    @DisplayName("grounded flow: scope resolved once, KG + vector evidence fuse, generate, cite, publish event")
    void groundedFlow() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        KnowledgeContext knowledge = new KnowledgeContext(
                List.of(new MatchedTopic(topicId, "IALCHEM2018-U1-T3",
                        "Bonding and Structure", 0.5)),
                List.of(), List.of());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(knowledge);
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "ms-1", 2, UUID.randomUUID(), 4,
                        "MARK_SCHEME", "electron pair repulsion determines shape", 6, 6,
                        List.of(), "gemini", 0.81)));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("learner brief", "knowledge brief", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("Bonding is directional [1].", "model-x",
                        "groq"));

        TutorAnswerView answer = service.ask(learnerId, "bonding question");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.answer()).isEqualTo("Bonding is directional [1].");
        assertThat(answer.evidenceCount()).isEqualTo(2);
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.topics()).hasSize(1);
        assertThat(answer.model()).isEqualTo("model-x");
        // chunk evidence is stamped with the intent-matched topics (v0 semantics)
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        TutorAnsweredEvent event = (TutorAnsweredEvent) eventCaptor.getValue();
        assertThat(event.learnerId()).isEqualTo(learnerId);
        assertThat(event.evidenceCount()).isEqualTo(2);
        assertThat(event.refused()).isFalse();
        assertThat(event.answerModel()).isEqualTo("model-x");
        assertThat(event.promptVersion()).isEqualTo("tutor-grounded/v5");
        // the single-turn overload is an unpersisted ask (s140: no session)
        assertThat(event.sessionId()).isNull();

        // context assembly saw the evidence capped and topic-stamped
        ArgumentCaptor<List<EvidenceItem>> evidenceCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(contextAssembler).assemble(any(), evidenceCaptor.capture(), any());
        assertThat(evidenceCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("grounding gate: zero evidence → deterministic refusal, NO LLM call")
    void refusalOnEmptyEvidence() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());

        TutorAnswerView answer = service.ask(learnerId, "photosynthesis in plants");

        assertThat(answer.refused()).isTrue();
        assertThat(answer.answer()).contains("can't answer that from the validated course material");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.evidenceCount()).isZero();
        assertThat(answer.provider()).isEqualTo("deterministic-refusal");
        verify(generator, never()).generate(anyString(), any());
        verify(contextAssembler, never()).assemble(any(), any(), any());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        TutorAnsweredEvent event = (TutorAnsweredEvent) eventCaptor.getValue();
        assertThat(event.refused()).isTrue();
        assertThat(event.evidenceCount()).isZero();
    }

    @Test
    @DisplayName("T-C07: unresolved curriculum scope → deterministic refusal, retrievers never called")
    void refusalOnUnresolvedScope() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.empty());

        TutorAnswerView answer = service.ask(learnerId, "bonding question");

        assertThat(answer.refused()).isTrue();
        assertThat(answer.answer()).contains("can't answer that from the validated course material");
        assertThat(answer.evidenceCount()).isZero();
        assertThat(answer.provider()).isEqualTo("deterministic-refusal");
        verify(knowledgeRetriever, never()).retrieve(anyString(), anyInt(), any());
        verify(vectorRetriever, never()).retrieve(anyString(), anyInt(), any());
        verify(generator, never()).generate(anyString(), any());

        // the refusal still publishes its research event (honest telemetry)
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((TutorAnsweredEvent) eventCaptor.getValue()).refused()).isTrue();
    }

    @Test
    @DisplayName("T-C07: the SAME resolved scope reaches both retrieval surfaces")
    void sameScopeReachesBothSurfaces() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());

        service.ask(learnerId, "anything");

        verify(knowledgeRetriever).retrieve("anything", 5, SCOPE);
        verify(vectorRetriever).retrieve("anything", 12, SCOPE);
    }

    @Test
    @DisplayName("evidenceLimit caps the final evidence set")
    void evidenceCapped() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "d1", 1, UUID.randomUUID(), 0,
                        "MARK_SCHEME", "one", 1, 1, List.of(), "m", 0.9),
                EvidenceItem.fromChunk(UUID.randomUUID(), "d2", 1, UUID.randomUUID(), 1,
                        "MARK_SCHEME", "two", 2, 2, List.of(), "m", 0.8),
                EvidenceItem.fromChunk(UUID.randomUUID(), "d3", 1, UUID.randomUUID(), 2,
                        "MARK_SCHEME", "three", 3, 3, List.of(), "m", 0.7)));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("b", "k", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("answer", "m", "p"));

        KaRagService capped = new KaRagService(knowledgeRetriever, vectorRetriever,
                paperQuestionResolver, curriculumScopes, fusion,
                reranker, contextAssembler, generator, citationResolver, events, 5, 12, 2);
        TutorAnswerView answer = capped.ask(learnerId, "question");

        assertThat(answer.evidenceCount()).isEqualTo(2);
        assertThat(answer.citations()).hasSize(2);
    }

    @Test
    @DisplayName("blank questions are rejected before any pipeline work")
    void blankQuestionRejected() {
        assertThatThrownBy(() -> service.ask(learnerId, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(knowledgeRetriever, never()).retrieve(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("anonymous ask (null learner) flows through with a null learner event")
    void anonymousAsk() {
        when(curriculumScopes.resolveActive(null)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(new MatchedTopic(topicId, "C", "T", 0.4)),
                        List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("anon", "k", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("answer", "m", "p"));

        TutorAnswerView answer = service.ask(null, "bonding");

        assertThat(answer.refused()).isFalse();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((TutorAnsweredEvent) eventCaptor.getValue()).learnerId()).isNull();
    }

    @Test
    @DisplayName("paper-question resolver: pinned lead evidence precedes fused evidence and dedup keeps the lead")
    void pinnedLeadEvidencePrecedesFused() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(new MatchedTopic(topicId, "C", "T", 0.4)),
                        List.of(), List.of()));
        UUID pinnedChunk = UUID.randomUUID();
        EvidenceItem pinned = EvidenceItem.fromChunk(UUID.randomUUID(), "qp-1", 1, pinnedChunk, 7,
                "QUESTION_PAPER", "10 (a) The diagram shows the apparatus", 3, 3,
                List.of(), "paper-question-resolver", 1.0);
        when(paperQuestionResolver.resolveWithVerdict(
                eq("explain question 10 from june 2019 paper 2"), eq(SCOPE)))
                .thenReturn(PaperQuestionResolver.Resolution.served(List.of(pinned),
                        "question 10 from the June 2019 paper 2"));
        // the vector arm surfaces the SAME chunk (dedup must keep the pinned lead)
        // plus one fusion-only chunk
        EvidenceItem duplicate = pinned.withFusedScore(0.9);
        EvidenceItem vectorOnly = EvidenceItem.fromChunk(UUID.randomUUID(), "ms-1", 1,
                UUID.randomUUID(), 2, "MARK_SCHEME", "silver chloride", 4, 4,
                List.of(), "gemini", 0.8);
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(List.of(vectorOnly, duplicate));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("l", "k", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("grounded", "m", "p"));

        TutorAnswerView answer = service.ask(learnerId, "explain question 10 from june 2019 paper 2");

        assertThat(answer.refused()).isFalse();
        ArgumentCaptor<List<EvidenceItem>> evidenceCaptor = ArgumentCaptor.forClass(List.class);
        verify(contextAssembler).assemble(any(), evidenceCaptor.capture(), any());
        List<EvidenceItem> assembled = evidenceCaptor.getValue();
        // pinned lead first, duplicate removed, vector-only chunk survives, KG anchor in between
        assertThat(assembled).extracting(EvidenceItem::chunkId)
                .startsWith(pinnedChunk)
                .doesNotHaveDuplicates();
        assertThat(assembled).anyMatch(item -> item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE);
        assertThat(assembled).anyMatch(item -> item.chunkId() != null
                && !item.chunkId().equals(pinnedChunk));
        assertThat(assembled.size()).isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("resolver miss keeps the pipeline byte-identical: no pinned evidence, fusion as before")
    void resolverMissLeavesPipelineUnchanged() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "qp-1", 1, UUID.randomUUID(), 0,
                        "QUESTION_PAPER", "states of matter", 1, 1, List.of(), "gemini", 0.7)));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("l", "k", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("a", "m", "p"));

        TutorAnswerView answer = service.ask(learnerId, "explain states of matter");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.evidenceCount()).isEqualTo(1);
    }

    // ── s139 working memory ────────────────────────────────────────────────────

    private void stubGroundedFollowUpFlow() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(new MatchedTopic(topicId, "C", "T", 0.4)),
                        List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "ms-9", 1, UUID.randomUUID(), 3,
                        "MARK_SCHEME", "moles = mass / Mr", 2, 2, List.of(), "gemini", 0.8)));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("l", "k", List.of()));
        when(generator.generate(anyString(), any(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("Follow-up answered [1].", "model-x", "groq"));
    }

    @Test
    @DisplayName("working memory: a keyword-free follow-up retrieves on the enriched query, generates on the raw question")
    void followUpRetrievalQueryCarriesConversationKeywords() {
        stubGroundedFollowUpFlow();
        List<ConversationTurn> history = List.of(
                new ConversationTurn(ConversationTurn.ROLE_USER,
                        "How do I calculate moles from mass and Mr?"),
                new ConversationTurn(ConversationTurn.ROLE_ASSISTANT,
                        "Use $n = m/Mr$ — divide mass by molar mass [1] [2]."));

        TutorAnswerView answer = service.ask(learnerId, "why is that?", history);

        assertThat(answer.refused()).isFalse();
        // retrieval saw the conversation's vocabulary around the bare follow-up…
        ArgumentCaptor<String> kgQuery = ArgumentCaptor.forClass(String.class);
        verify(knowledgeRetriever).retrieve(kgQuery.capture(), anyInt(), eq(SCOPE));
        assertThat(kgQuery.getValue()).contains("moles").contains("why is that?");
        ArgumentCaptor<String> vectorQuery = ArgumentCaptor.forClass(String.class);
        verify(vectorRetriever).retrieve(vectorQuery.capture(), anyInt(), eq(SCOPE));
        // …rendered oldest-first with the question last
        assertThat(vectorQuery.getValue()).endsWith("why is that?");
        // …while generation answered the RAW follow-up with the history beside it
        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> historyCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(generator).generate(question.capture(), historyCaptor.capture(), any());
        assertThat(question.getValue()).isEqualTo("why is that?");
        assertThat(historyCaptor.getValue()).hasSize(2);
        // the research record marks the exchange as a follow-up
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((TutorAnsweredEvent) eventCaptor.getValue()).historyTurns()).isEqualTo(2);
    }

    @Test
    @DisplayName("s140: a session-anchored ask publishes the sessionId — the pipeline itself is unchanged")
    void sessionAnchoredAskPublishesSessionId() {
        stubGroundedFollowUpFlow();
        UUID sessionId = UUID.randomUUID();

        TutorAnswerView answer = service.ask(learnerId, "moles question", List.of(), sessionId);

        assertThat(answer.refused()).isFalse();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        TutorAnsweredEvent event = (TutorAnsweredEvent) eventCaptor.getValue();
        assertThat(event.sessionId()).isEqualTo(sessionId);
        assertThat(event.historyTurns()).isZero();
        // single-turn + session: generation saw no conversation block
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> historyCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(generator).generate(anyString(), historyCaptor.capture(), any());
        assertThat(historyCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("history is sanitized server-side: capped at 12 turns, markers stripped, oversized turns bounded")
    void historySanitizedBeforePipelineUse() {
        stubGroundedFollowUpFlow();
        List<ConversationTurn> history = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            history.add(new ConversationTurn(ConversationTurn.ROLE_USER, "turn " + i));
        }
        // the assistant turn that must lose its citation markers (its numbers
        // belong to sources absent from the next prompt)
        history.add(new ConversationTurn(ConversationTurn.ROLE_ASSISTANT,
                "The mole ratio is 2:1 [1] and [23] fixes it."));
        history.add(new ConversationTurn(ConversationTurn.ROLE_USER, "ok"));
        history.add(new ConversationTurn(ConversationTurn.ROLE_ASSISTANT,
                "x".repeat(ConversationTurn.MAX_TURN_CHARS + 500)));

        service.ask(learnerId, "and now?", history);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConversationTurn>> historyCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(generator).generate(anyString(), historyCaptor.capture(), any());
        List<ConversationTurn> sanitized = historyCaptor.getValue();
        assertThat(sanitized).hasSize(ConversationTurn.MAX_HISTORY_TURNS);
        assertThat(sanitized.get(sanitized.size() - 3).text())
                .isEqualTo("The mole ratio is 2:1 and fixes it.");
        assertThat(sanitized.get(sanitized.size() - 1).text().length())
                .isLessThanOrEqualTo(ConversationTurn.MAX_TURN_CHARS + 1);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((TutorAnsweredEvent) eventCaptor.getValue()).historyTurns())
                .isEqualTo(ConversationTurn.MAX_HISTORY_TURNS);
    }

    @Test
    @DisplayName("refusal with history stays deterministic: no LLM call, event still carries the turn count")
    void refusalWithHistoryStillDeterministic() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());

        TutorAnswerView answer = service.ask(learnerId, "why is that?", List.of(
                new ConversationTurn(ConversationTurn.ROLE_USER, "what is chromatography?"),
                new ConversationTurn(ConversationTurn.ROLE_ASSISTANT, "No grounded evidence yet.")));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.provider()).isEqualTo("deterministic-refusal");
        verify(generator, never()).generate(anyString(), any(), any());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        TutorAnsweredEvent event = (TutorAnsweredEvent) eventCaptor.getValue();
        assertThat(event.refused()).isTrue();
        assertThat(event.historyTurns()).isEqualTo(2);
    }

    // ── fail-open guard (09-27 adjudication, direction (a)) ───────────────────

    @Test
    @DisplayName("fail-open guard: a parsed identity that bound zero anchors refuses deterministically — "
            + "the vector arm's wrong-paper bleed never reaches the learner (09-27 adjudication)")
    void identityBoundUnservedRefusesInsteadOfBleeding() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        // the bleed: wrong-paper chunks the generic pool would have served with confidence
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "ms-bleed", 1, UUID.randomUUID(), 14,
                        "MARK_SCHEME", "6 bonding electrons, 2 non-bonding on each atom", 15, 15,
                        List.of(), "gemini", 0.83)));
        // the ask parses completely (question 10, JUN 2019, paper 2) but nothing validated binds
        when(paperQuestionResolver.resolveWithVerdict(
                eq("explain question 10 from june 2019 paper 2"), eq(SCOPE)))
                .thenReturn(new PaperQuestionResolver.Resolution(List.of(), true,
                        "question 10 from the June 2019 paper 2"));

        TutorAnswerView answer = service.ask(learnerId, "explain question 10 from june 2019 paper 2");

        assertThat(answer.refused()).isTrue();
        // the echoed identity sits on one line of the refusal text block
        assertThat(answer.answer()).contains("find question 10 from the June 2019 paper 2");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.evidenceCount()).isZero();
        assertThat(answer.provider()).isEqualTo("deterministic-paper-refusal");
        verify(generator, never()).generate(anyString(), any(), any());
        verify(contextAssembler, never()).assemble(any(), any(), any());

        // the refusal still publishes its research event (honest telemetry)
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        TutorAnsweredEvent event = (TutorAnsweredEvent) eventCaptor.getValue();
        assertThat(event.refused()).isTrue();
        assertThat(event.evidenceCount()).isZero();
    }

    @Test
    @DisplayName("fail-open guard does not touch the generic refusal: a non-paper ask keeps REFUSAL + marker")
    void nonPaperAskKeepsTheGenericRefusal() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        resolverReturns(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());

        TutorAnswerView answer = service.ask(learnerId, "photosynthesis in plants");

        assertThat(answer.refused()).isTrue();
        assertThat(answer.answer()).doesNotContain("could not find");
        assertThat(answer.provider()).isEqualTo("deterministic-refusal");
    }

    @Test
    @DisplayName("retrievalQuery: no history = verbatim question; the window keeps the newest turns and the question")
    void retrievalQueryBounds() {
        assertThat(KaRagService.retrievalQuery("bonding?", List.of())).isEqualTo("bonding?");
        assertThat(KaRagService.retrievalQuery("bonding?", null)).isEqualTo("bonding?");

        List<ConversationTurn> history = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new ConversationTurn(ConversationTurn.ROLE_USER, "turn" + i));
        }
        String enriched = KaRagService.retrievalQuery("why?", history);
        // only the last RETRIEVAL_WINDOW_TURNS turns ride along, oldest-first, question last
        assertThat(enriched).isEqualTo("turn6 turn7 turn8 turn9 why?");
        assertThat(enriched).doesNotContain("turn5");

        // a single huge turn is still included (one turn of context beats none),
        // but the total stays bounded by question + one capped turn
        String huge = "x".repeat(ConversationTurn.MAX_TURN_CHARS);
        String two = KaRagService.retrievalQuery("why?", List.of(
                new ConversationTurn(ConversationTurn.ROLE_USER, huge),
                new ConversationTurn(ConversationTurn.ROLE_ASSISTANT, huge)));
        assertThat(two.length())
                .isLessThanOrEqualTo("why?".length() + 1 + ConversationTurn.MAX_TURN_CHARS + 1);
    }
}
