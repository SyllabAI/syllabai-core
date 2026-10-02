package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.shared.events.TutorAnsweredEvent;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

/**
 * Streamed KA-RAG orchestration (tutor SSE tranche): the event sequence
 * (citations before generation, meta with the first token, deltas in order,
 * completed last), the research event published exactly once on completion,
 * and refusal streams byte-identical to the blocking path — all over the
 * SAME retrieval pipeline the blocking ask runs.
 */
class KaRagServiceStreamTest {

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

    private void scopeWithKnowledge() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        when(paperQuestionResolver.resolveWithVerdict(anyString(), any(CurriculumScope.class)))
                .thenReturn(PaperQuestionResolver.Resolution.notPaperAsk());
        KnowledgeRetriever.KnowledgeContext knowledge = new KnowledgeRetriever.KnowledgeContext(
                List.of(new KnowledgeRetriever.KnowledgeContext.MatchedTopic(
                        topicId, "IALCHEM2018-U1-T3", "Bonding and Structure", 0.5)),
                List.of(), List.of());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(knowledge);
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of(
                EvidenceItem.fromChunk(UUID.randomUUID(), "note-doc", 3, topicId, 5,
                        "NOTE", "ionic bonding transfers electrons", 3, 3,
                        List.of("e1"), "gemini", 0.7)));
        when(contextAssembler.assemble(any(), anyList(), eq(learnerId)))
                .thenReturn(new ContextAssembler.TutorContext("brief", "brief", List.of()));
    }

    @Test
    @DisplayName("served stream: citations → meta(first token) → deltas → completed, one research event")
    void servedStreamEventSequence() {
        scopeWithKnowledge();
        when(generator.streamGenerate(anyString(), anyList(), any()))
                .thenReturn(Flux.just(
                        new TutorGenerator.GeneratedDelta("Bonding transfers ", "llama-3", "groq"),
                        new TutorGenerator.GeneratedDelta("electrons [1].", "llama-3", "groq")));

        List<TutorStreamEvent> stream =
                service.askStream(learnerId, "What is ionic bonding?", List.of(), null)
                        .collectList().block();

        assertThat(stream).hasSize(5);
        assertThat(stream.get(0)).isInstanceOf(TutorStreamEvent.Citations.class);
        TutorStreamEvent.Citations citations = (TutorStreamEvent.Citations) stream.get(0);
        assertThat(citations.sufficient()).isTrue();
        assertThat(citations.citations()).isNotEmpty();

        // meta rides the FIRST delta
        assertThat(stream.get(1)).isInstanceOf(TutorStreamEvent.Meta.class);
        TutorStreamEvent.Meta meta = (TutorStreamEvent.Meta) stream.get(1);
        assertThat(meta.provider()).isEqualTo("groq");
        assertThat(meta.model()).isEqualTo("llama-3");
        assertThat(meta.refused()).isFalse();
        assertThat(meta.evidenceCount()).isEqualTo(1);

        assertThat(stream.get(2)).isEqualTo(new TutorStreamEvent.Delta("Bonding transfers "));
        assertThat(stream.get(3)).isEqualTo(new TutorStreamEvent.Delta("electrons [1]."));

        TutorStreamEvent.Completed done = (TutorStreamEvent.Completed) stream.get(4);
        assertThat(done.fullAnswer()).isEqualTo("Bonding transfers electrons [1].");
        assertThat(done.refused()).isFalse();
        assertThat(done.latencyMs()).isGreaterThanOrEqualTo(0);

        // exactly one research event, published on completion, full parity fields
        ArgumentCaptor<TutorAnsweredEvent> captor = ArgumentCaptor.forClass(TutorAnsweredEvent.class);
        verify(events).publishEvent(captor.capture());
        TutorAnsweredEvent event = captor.getValue();
        assertThat(event.refused()).isFalse();
        assertThat(event.answerProvider()).isEqualTo("groq");
        assertThat(event.answerModel()).isEqualTo("llama-3");
        assertThat(event.evidenceCount()).isEqualTo(1);
        assertThat(event.question()).isEqualTo("What is ionic bonding?");
        assertThat(event.promptVersion()).isEqualTo(GroundedTutorGenerator.promptIdentity());
    }

    @Test
    @DisplayName("refusal stream: deterministic text as a single delta, byte-identical to /ask")
    void refusalStream() {
        when(curriculumScopes.resolveActive(learnerId)).thenReturn(Optional.of(SCOPE));
        when(paperQuestionResolver.resolveWithVerdict(anyString(), any(CurriculumScope.class)))
                .thenReturn(PaperQuestionResolver.Resolution.notPaperAsk());
        when(knowledgeRetriever.retrieve(anyString(), anyInt(), eq(SCOPE)))
                .thenReturn(new KnowledgeRetriever.KnowledgeContext(List.of(), List.of(), List.of()));
        when(vectorRetriever.retrieve(anyString(), anyInt(), eq(SCOPE))).thenReturn(List.of());

        List<TutorStreamEvent> stream =
                service.askStream(learnerId, "What is nuclear fusion?", List.of(), null)
                        .collectList().block();

        assertThat(stream).hasSize(4);
        assertThat(stream.get(0)).isInstanceOf(TutorStreamEvent.Citations.class);
        assertThat(((TutorStreamEvent.Citations) stream.get(0)).citations()).isEmpty();
        TutorStreamEvent.Meta meta = (TutorStreamEvent.Meta) stream.get(1);
        assertThat(meta.refused()).isTrue();
        assertThat(meta.provider()).isEqualTo("deterministic-refusal");
        assertThat(stream.get(2)).isEqualTo(new TutorStreamEvent.Delta(KaRagService.REFUSAL));
        assertThat(((TutorStreamEvent.Completed) stream.get(3)).fullAnswer())
                .isEqualTo(KaRagService.REFUSAL);

        ArgumentCaptor<TutorAnsweredEvent> captor = ArgumentCaptor.forClass(TutorAnsweredEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().refused()).isTrue();
        assertThat(captor.getValue().answerProvider()).isEqualTo("deterministic-refusal");
        // never touched the LLM (the deterministic gate refuses before generation)
        verify(generator, never()).streamGenerate(anyString(), anyList(), any());
    }

    @Test
    @DisplayName("mid-stream generation failure: error propagates, NO research event, NO completion")
    void midStreamFailurePersistsNothing() {
        scopeWithKnowledge();
        when(generator.streamGenerate(anyString(), anyList(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(new TutorGenerator.GeneratedDelta("partial ", "llama-3", "groq")),
                        Flux.error(new TutorGenerationException(
                                GroundedTutorGenerator.UNAVAILABLE_MESSAGE))));

        List<reactor.core.publisher.Signal<TutorStreamEvent>> signals =
                service.askStream(learnerId, "q", List.of(), null)
                        .materialize().collectList().block();

        assertThat(signals).hasSize(4); // citations, meta(first delta), delta, error
        assertThat(signals.get(2).get()).isEqualTo(new TutorStreamEvent.Delta("partial "));
        assertThat(signals.get(3).isOnError()).isTrue();
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("L2: an EMPTY generation (zero tokens) is a failure — no meta, no blank done, "
            + "no research event, nothing persists")
    void emptyGenerationSurfacesAsFailure() {
        scopeWithKnowledge();
        when(generator.streamGenerate(anyString(), anyList(), any()))
                .thenReturn(Flux.empty());

        List<reactor.core.publisher.Signal<TutorStreamEvent>> signals =
                service.askStream(learnerId, "q", List.of(), null)
                        .materialize().collectList().block();

        assertThat(signals).hasSize(2); // citations, then the error — never a blank completion
        assertThat(signals.get(0).get()).isInstanceOf(TutorStreamEvent.Citations.class);
        assertThat(signals.get(1).isOnError()).isTrue();
        assertThat(signals.get(1).getThrowable())
                .isInstanceOf(TutorGenerationException.class);
        // nothing persisted — no research event was published for the blank stream
        verify(events, never()).publishEvent(any());
    }
}
