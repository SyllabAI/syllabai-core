package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 2 §7 queue intelligence: the v3 review queue computes its signals
 * from the batched repositories, ranks deterministically, and states ONLY the
 * reasons that actually hold — absent confidence is never zero-filled, novel
 * coverage is measured against what validated content can already serve.
 * Ordering is a triage aid; nothing is promoted.
 */
class ContentReviewServiceV3Test {

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final GlmOcrBridgeRecordRepository bridgeRecords =
            mock(GlmOcrBridgeRecordRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionTopicRepository questionTopics =
            mock(QuestionTopicRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes =
            mock(KnowledgeNodeRepository.class);
    private final ContentAuditRecorder auditRecorder = mock(ContentAuditRecorder.class);
    private final ContentReviewAuditRepository auditRepository =
            mock(ContentReviewAuditRepository.class);
    private final ContentReviewService service =
            new ContentReviewService(examPapers, questionVersions, markSchemes, markPoints,
                    subjects, bridgeRecords, questions, questionTopics, knowledgeNodes,
                    auditRecorder, auditRepository);

    private static final Instant T = Instant.now().minus(3, ChronoUnit.DAYS);

    /** a SUGGESTED paper mock with an id, title and deterministic timestamps */
    private ExamPaper suggestedPaper(UUID id, String title, Instant createdAt) {
        ExamPaper paper = mock(ExamPaper.class);
        lenient().when(paper.id()).thenReturn(id);
        lenient().when(paper.subjectId()).thenReturn(UUID.randomUUID());
        lenient().when(paper.title()).thenReturn(title);
        lenient().when(paper.paperCode()).thenReturn("4CH1/1C");
        lenient().when(paper.sessionLabel()).thenReturn("June 2019");
        lenient().when(paper.board()).thenReturn("Edexcel");
        lenient().when(paper.qualification()).thenReturn("IGCSE");
        lenient().when(paper.validationState())
                .thenReturn(ExamPaper.ValidationState.SUGGESTED);
        lenient().when(paper.createdAt()).thenReturn(createdAt);
        return paper;
    }

    private void stageBaseQueue(List<ExamPaper> suggested,
                                Map<UUID, Double> confidence) {
        // materialize the rows BEFORE stubbing: the rows call mock methods
        // (p.id()), which must never run inside an unfinished when(...)
        List<Object[]> versionRows = new ArrayList<>();
        List<Object[]> schemeRows = new ArrayList<>();
        List<Object[]> confidenceRows = new ArrayList<>();
        for (ExamPaper p : suggested) {
            versionRows.add(new Object[]{p.id(),
                    QuestionVersion.ValidationState.SUGGESTED, 4L});
            schemeRows.add(new Object[]{p.id(),
                    MarkScheme.ValidationState.SUGGESTED, 2L});
        }
        for (Map.Entry<UUID, Double> e : confidence.entrySet()) {
            confidenceRows.add(new Object[]{e.getKey(), e.getValue()});
        }
        when(examPapers.findSuggested()).thenReturn(suggested);
        when(questionVersions.countByPaperAndState()).thenReturn(versionRows);
        when(markSchemes.countByPaperAndState()).thenReturn(schemeRows);
        when(questionVersions.avgExtractionConfidenceByPaper()).thenReturn(confidenceRows);
        suggested.forEach(p ->
                lenient().when(bridgeRecords.findByPaperId(p.id()))
                        .thenReturn(Optional.empty()));
    }

    @Test
    @DisplayName("v3 ranks fully-schemed, fully-mapped papers first with reasons stated")
    void v3SignalsComputedAndRanked() {
        UUID strongId = UUID.randomUUID();
        UUID weakId = UUID.randomUUID();
        ExamPaper strong = suggestedPaper(strongId, "Strong import", T);
        ExamPaper weak = suggestedPaper(weakId, "Weak import", T.plusSeconds(60));
        stageBaseQueue(List.of(weak, strong), Map.of(
                strongId, 0.8, weakId, 0.9));   // weak has HIGHER confidence

        // schemes: strong 4/4, weak 0/4
        when(markSchemes.countQuestionsWithSchemesByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{strongId, 4L}));
        // census: [total, mapped] — strong 4/4 mapped, weak 4/0
        when(questions.countAndMappedByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{strongId, 4L, 4L},
                new Object[]{weakId, 4L, 0L}));
        // mappings: strong → 2 topics, weak → none
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(questionTopics.findMappingsByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{strongId, t1}, new Object[]{strongId, t2}));
        when(examPapers.findValidated()).thenReturn(List.of());
        when(questions.findDistinctPrimaryTopicsByPaperIds(
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        var view = service.enrichedReviewQueueV3();

        assertThat(view.papers()).hasSize(2);
        // scheme/mapping ratios dominate raw confidence → strong first
        assertThat(view.papers().get(0).id()).isEqualTo(strongId);
        assertThat(view.papers().get(0).totalQuestions()).isEqualTo(4);
        assertThat(view.papers().get(0).mappedQuestions()).isEqualTo(4);
        assertThat(view.papers().get(0).questionsWithScheme()).isEqualTo(4);
        assertThat(view.papers().get(0).novelTopicCount()).isEqualTo(2);
        assertThat(view.papers().get(0).rankReasons())
                .contains("mark scheme linked for all 4 question(s)",
                        "all 4 question(s) mapped to curriculum",
                        "brings 2 topic(s) not yet practicable from validated content",
                        "mean extraction confidence 0.80");
        // the weaker import still ranks, honestly
        assertThat(view.papers().get(1).id()).isEqualTo(weakId);
        assertThat(view.papers().get(1).rankReasons())
                .contains("no mark scheme linked yet");
        assertThat(view.practicableTopicCount()).isZero();
    }

    @Test
    @DisplayName("novel coverage counts only topics validated content cannot serve")
    void novelCoverageAgainstPracticableSet() {
        UUID candidateId = UUID.randomUUID();
        ExamPaper candidate = suggestedPaper(candidateId, "Candidate", T);
        stageBaseQueue(List.of(candidate), Map.of(candidateId, 0.6));

        when(markSchemes.countQuestionsWithSchemesByPaper()).thenReturn(List.of());
        when(questions.countAndMappedByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{candidateId, 3L, 3L}));

        UUID servedTopic = UUID.randomUUID();       // already practicable
        UUID novelTopic = UUID.randomUUID();        // genuinely new
        when(questionTopics.findMappingsByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{candidateId, servedTopic},
                new Object[]{candidateId, novelTopic}));

        // the validated paper already serves servedTopic (primary) — its mapped
        // rows also feed the practicable set
        UUID validatedId = UUID.randomUUID();
        ExamPaper validated = mock(ExamPaper.class);
        lenient().when(validated.id()).thenReturn(validatedId);
        when(examPapers.findValidated()).thenReturn(List.of(validated));
        when(questions.findDistinctPrimaryTopicsByPaperIds(
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(servedTopic));

        var view = service.enrichedReviewQueueV3();

        assertThat(view.papers().get(0).novelTopicCount()).isEqualTo(1);
        assertThat(view.practicableTopicCount()).isEqualTo(1);
        assertThat(view.papers().get(0).rankReasons())
                .contains("brings 1 topic(s) not yet practicable from validated content");
    }

    @Test
    @DisplayName("absent confidence stays null and is never stated as a reason")
    void absentConfidenceNotFabricated() {
        UUID id = UUID.randomUUID();
        stageBaseQueue(List.of(suggestedPaper(id, "No confidence", T)), Map.of());
        when(markSchemes.countQuestionsWithSchemesByPaper()).thenReturn(List.of());
        when(questions.countAndMappedByPaper()).thenReturn(List.of());
        when(questionTopics.findMappingsByPaper()).thenReturn(List.of());
        when(examPapers.findValidated()).thenReturn(List.of());
        when(questions.findDistinctPrimaryTopicsByPaperIds(
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        var view = service.enrichedReviewQueueV3();

        assertThat(view.papers().get(0).avgExtractionConfidence()).isNull();
        assertThat(view.papers().get(0).rankReasons())
                .noneMatch(r -> r.contains("confidence"));
        // no questions → no scheme/mapping claims either
        assertThat(view.papers().get(0).rankReasons())
                .noneMatch(r -> r.contains("mark scheme") || r.contains("mapped"));
    }

    @Test
    @DisplayName("reconciled-OK papers sort before non-OK regardless of other signals")
    void reconciledOkFirst() {
        UUID okId = UUID.randomUUID();
        UUID notOkId = UUID.randomUUID();
        ExamPaper ok = suggestedPaper(okId, "OK bridge", T);
        ExamPaper notOk = suggestedPaper(notOkId, "REVIEW_REQUIRED bridge", T);
        when(examPapers.findSuggested()).thenReturn(List.of(notOk, ok));
        when(questionVersions.countByPaperAndState()).thenReturn(List.of());
        when(markSchemes.countByPaperAndState()).thenReturn(List.of());
        when(questionVersions.avgExtractionConfidenceByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{notOkId, 0.99}));   // non-OK has confidence

        GlmOcrBridgeRecordRepository bridges = bridgeRecords;
        com.syllabai.teacher.ingestion.GlmOcrBridgeRecord okBridge =
                mock(com.syllabai.teacher.ingestion.GlmOcrBridgeRecord.class);
        when(okBridge.reconciliationStatus()).thenReturn("OK");
        when(bridges.findByPaperId(okId)).thenReturn(Optional.of(okBridge));
        com.syllabai.teacher.ingestion.GlmOcrBridgeRecord notOkBridge =
                mock(com.syllabai.teacher.ingestion.GlmOcrBridgeRecord.class);
        when(notOkBridge.reconciliationStatus()).thenReturn("REVIEW_REQUIRED");
        when(bridges.findByPaperId(notOkId)).thenReturn(Optional.of(notOkBridge));

        // non-OK has the better §7 signals…
        when(markSchemes.countQuestionsWithSchemesByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{notOkId, 5L}));
        when(questions.countAndMappedByPaper()).thenReturn(List.<Object[]>of(
                new Object[]{notOkId, 5L, 5L}));
        when(questionTopics.findMappingsByPaper()).thenReturn(List.of());
        when(examPapers.findValidated()).thenReturn(List.of());
        when(questions.findDistinctPrimaryTopicsByPaperIds(
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        var view = service.enrichedReviewQueueV3();

        // …but reconciliation risk dominates: OK first
        assertThat(view.papers().get(0).id()).isEqualTo(okId);
        assertThat(view.papers().get(1).id()).isEqualTo(notOkId);
    }

    @Test
    @DisplayName("ordering is deterministic across calls")
    void orderingDeterministicAcrossCalls() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        stageBaseQueue(List.of(suggestedPaper(b, "B", T),
                        suggestedPaper(a, "A", T.plusSeconds(1)),
                        suggestedPaper(c, "C", T.plusSeconds(2))),
                Map.of(a, 0.5, b, 0.7, c, 0.6));
        when(markSchemes.countQuestionsWithSchemesByPaper()).thenReturn(List.of());
        when(questions.countAndMappedByPaper()).thenReturn(List.of());
        when(questionTopics.findMappingsByPaper()).thenReturn(List.of());
        when(examPapers.findValidated()).thenReturn(List.of());
        when(questions.findDistinctPrimaryTopicsByPaperIds(
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        var first = service.enrichedReviewQueueV3();
        var second = service.enrichedReviewQueueV3();

        assertThat(first.papers()).extracting(p -> p.id())
                .containsExactlyElementsOf(
                        second.papers().stream().map(p -> p.id()).toList());
    }
}
