package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.events.TutorAnsweredEvent;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MatchedTopic;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * KA-RAG orchestration (T-024): the full pipeline composes intent → hybrid
 * retrieval → fusion → rerank → assembly → generation → citations, the
 * grounding gate refuses deterministically on empty evidence (no LLM call),
 * and every outcome publishes the research event.
 */
class KaRagServiceTest {

    private final KnowledgeRetriever knowledgeRetriever = mock(KnowledgeRetriever.class);
    private final VectorRetriever vectorRetriever = mock(VectorRetriever.class);
    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);
    private final EvidenceReranker reranker = new NoReranker();
    private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
    private final TutorGenerator generator = mock(TutorGenerator.class);
    private final CitationResolver citationResolver = new SimpleCitationResolver();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final KaRagService service = new KaRagService(knowledgeRetriever, vectorRetriever,
            fusion, reranker, contextAssembler, generator, citationResolver, events, 5, 12, 6);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID topicId = UUID.randomUUID();

    @Test
    @DisplayName("grounded flow: KG + vector evidence fuse, generate, cite, publish event")
    void groundedFlow() {
        KnowledgeContext knowledge = new KnowledgeContext(
                List.of(new MatchedTopic(topicId, "IALCHEM2018-U1-T3",
                        "Bonding and Structure", 0.5)),
                List.of(), List.of());
        when(knowledgeRetriever.retrieve(anyString(), anyInt())).thenReturn(knowledge);
        when(vectorRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "ms-1", 2, UUID.randomUUID(), 4,
                        "MARK_SCHEME", "electron pair repulsion determines shape", 6, 6,
                        List.of(), "gemini", 0.81)));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("learner brief", "knowledge brief", List.of()));
        when(generator.generate(anyString(), any())).thenReturn(
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
        assertThat(event.promptVersion()).isEqualTo("tutor-grounded/v2");

        // context assembly saw the evidence capped and topic-stamped
        ArgumentCaptor<List<EvidenceItem>> evidenceCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(contextAssembler).assemble(any(), evidenceCaptor.capture(), any());
        assertThat(evidenceCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("grounding gate: zero evidence → deterministic refusal, NO LLM call")
    void refusalOnEmptyEvidence() {
        when(knowledgeRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());

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
    @DisplayName("evidenceLimit caps the final evidence set")
    void evidenceCapped() {
        when(knowledgeRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(new KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "d1", 1, UUID.randomUUID(), 0,
                        "MARK_SCHEME", "one", 1, 1, List.of(), "m", 0.9),
                EvidenceItem.fromChunk(UUID.randomUUID(), "d2", 1, UUID.randomUUID(), 1,
                        "MARK_SCHEME", "two", 2, 2, List.of(), "m", 0.8),
                EvidenceItem.fromChunk(UUID.randomUUID(), "d3", 1, UUID.randomUUID(), 2,
                        "MARK_SCHEME", "three", 3, 3, List.of(), "m", 0.7)));
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("b", "k", List.of()));
        when(generator.generate(anyString(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("answer", "m", "p"));

        KaRagService capped = new KaRagService(knowledgeRetriever, vectorRetriever, fusion,
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
        verify(knowledgeRetriever, never()).retrieve(anyString(), anyInt());
    }

    @Test
    @DisplayName("anonymous ask (null learner) flows through with a null learner event")
    void anonymousAsk() {
        when(knowledgeRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(new KnowledgeContext(List.of(new MatchedTopic(topicId, "C", "T", 0.4)),
                        List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), any(), any())).thenReturn(
                new ContextAssembler.TutorContext("anon", "k", List.of()));
        when(generator.generate(anyString(), any())).thenReturn(
                new TutorGenerator.GeneratedAnswer("answer", "m", "p"));

        TutorAnswerView answer = service.ask(null, "bonding");

        assertThat(answer.refused()).isFalse();
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((TutorAnsweredEvent) eventCaptor.getValue()).learnerId()).isNull();
    }
}
