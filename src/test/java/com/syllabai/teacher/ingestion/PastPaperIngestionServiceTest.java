package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.shared.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-011 ingestion bridge: a parser draft lands as an all-SUGGESTED paper with
 * structured questions, parts and part-scoped mark points; duplicate ingestion
 * is rejected; drafts without content are rejected.
 */
class PastPaperIngestionServiceTest {

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);

    private final PastPaperIngestionService service = new PastPaperIngestionService(
            examPapers, questions, questionVersions, markSchemes, subjects,
            curriculumVersions, knowledgeNodes, knowledgeEdges);

    private final List<Object> savedAll = new ArrayList<>();

    {
        when(examPapers.save(any(ExamPaper.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(questions.save(any(Question.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(markSchemes.save(any(MarkScheme.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(knowledgeNodes.save(any(KnowledgeNode.class)))
                .thenAnswer(inv -> capture(TestIds.withId(inv.getArgument(0), UUID.randomUUID())));
        when(curriculumVersions.save(any(CurriculumVersion.class)))
                .thenAnswer(inv -> capture(inv.getArgument(0)));
        when(subjects.save(any(Subject.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(examPapers.findByPaperCodeAndSessionLabel(any(), any())).thenReturn(Optional.empty());
        when(subjects.findByCode(any())).thenReturn(Optional.empty());
        when(curriculumVersions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(questionVersions.save(any(com.syllabai.assessment.QuestionVersion.class)))
                .thenAnswer(inv -> capture(inv.getArgument(0)));
    }

    private <T> T capture(T entity) {
        savedAll.add(entity);
        return entity;
    }

    private PastPaperDraftDto draft() {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry", "Paper 1C",
                        "January 2012", "4CH0/1C", "qp-doc-1", "ms-doc-1"),
                List.of(
                        new PastPaperDraftDto.QuestionDraft("q3", "3",
                                "Question 3 stem about rusting", null, 3, "STRUCTURED",
                                2, 0.55,
                                List.of(
                                        new PastPaperDraftDto.PartDraft("a", "What is rust?",
                                                "State", 1, 0.5),
                                        new PastPaperDraftDto.PartDraft("b",
                                                "Explain galvanising.", "Explain", 2, 0.5)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "ms-doc-1", List.of(
                        new PastPaperDraftDto.MarkPointDraft("3-a", 1, "iron(III) oxide", 1,
                                List.of(), 0.55),
                        new PastPaperDraftDto.MarkPointDraft("3-b", 1, "zinc layer prevents air contact", 1,
                                List.of(), 0.55),
                        new PastPaperDraftDto.MarkPointDraft("9", 1, "question-level orphan point", 1,
                                List.of(), 0.55))),
                "opendataloader-fast+heuristics-v0",
                true);
    }

    @Test
    @DisplayName("draft lands as one SUGGESTED paper with parts and part-scoped mark points")
    void ingestCreatesSuggestedContent() {
        PastPaperIngestionService.IngestionSummary summary =
                service.ingest(draft(), UUID.randomUUID());

        assertThat(summary.questions()).isEqualTo(1);
        assertThat(summary.parts()).isEqualTo(2);
        assertThat(summary.markPoints()).isEqualTo(2);   // "9" belongs to no question here

        ExamPaper saved = (ExamPaper) savedAll.stream()
                .filter(e -> e instanceof ExamPaper).findFirst().orElseThrow();
        assertThat(saved.validationState()).isEqualTo(ExamPaper.ValidationState.SUGGESTED);
        assertThat(saved.provenance()).isEqualTo(ExamPaper.Provenance.PAST_PAPER);
        assertThat(saved.questionPaperDocumentId()).isEqualTo("qp-doc-1");

        Question question = (Question) savedAll.stream()
                .filter(e -> e instanceof Question).findFirst().orElseThrow();
        assertThat(question.type()).isEqualTo(Question.Type.STRUCTURED);
        assertThat(question.examPaperId()).isEqualTo(saved.id());
        assertThat(question.provenance()).isEqualTo(Question.Provenance.PAST_PAPER);

        MarkScheme scheme = (MarkScheme) savedAll.stream()
                .filter(e -> e instanceof MarkScheme).findFirst().orElseThrow();
        assertThat(scheme.validationState()).isEqualTo(MarkScheme.ValidationState.SUGGESTED);
        assertThat(scheme.points()).hasSize(2);
        assertThat(scheme.points().get(0).questionPart()).isNotNull();   // "3-a" -> part "a"
        assertThat(scheme.points().get(0).questionPart().label()).isEqualTo("a");
        assertThat(scheme.points().get(1).questionPart().label()).isEqualTo("b");
    }

    @Test
    @DisplayName("re-ingesting the same paper+session is rejected")
    void duplicateRejected() {
        when(examPapers.findByPaperCodeAndSessionLabel("4CH0/1C", "January 2012"))
                .thenReturn(Optional.of(new ExamPaper(
                        UUID.randomUUID(), "t", "b", "q", null, null, null, null, null,
                        ExamPaper.Provenance.PAST_PAPER, null, null)));

        assertThatThrownBy(() -> service.ingest(draft(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already ingested");
    }

    @Test
    @DisplayName("a draft with no questions is rejected")
    void emptyDraftRejected() {
        PastPaperDraftDto empty = new PastPaperDraftDto("1.0",
                draft().paper(), List.of(), null, "m", true);
        assertThatThrownBy(() -> service.ingest(empty, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no questions");
    }

    // ── ingestion-anchor find-or-create (regression: uq_knowledge_node_code) ──

    /** two papers of the SAME exam session derive the same anchor code (e.g. ING-SUMMER2013).
     *  paperCode is null on purpose: that is the r1 collision shape — papers whose
     *  drafts carry no printed paper reference fall back to session-label identity. */
    private PastPaperDraftDto sameSessionDraft(String unit, String docSuffix) {
        return new PastPaperDraftDto("1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry", unit,
                        "Summer 2013", null, "qp-doc-" + docSuffix, "ms-doc-" + docSuffix),
                List.of(new PastPaperDraftDto.QuestionDraft("q1", "1", "Stem " + docSuffix,
                        null, 2, "STRUCTURED", 1, 0.9,
                        List.of(new PastPaperDraftDto.PartDraft("a", "Part " + docSuffix,
                                null, 1, 0.9)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "ms-doc-" + docSuffix,
                        List.of(new PastPaperDraftDto.MarkPointDraft("1-a", 1,
                                "answer " + docSuffix, 1, List.of(), 0.9))),
                "opendataloader-fast+heuristics-v0", true);
    }

    private void inMemoryAnchors(Map<String, KnowledgeNode> anchors) {
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.ofNullable(anchors.get(inv.getArgument(0, String.class))));
        when(knowledgeNodes.save(any(KnowledgeNode.class))).thenAnswer(inv -> {
            KnowledgeNode node = TestIds.withId(inv.getArgument(0, KnowledgeNode.class),
                    UUID.randomUUID());
            anchors.put(node.code(), node);
            return capture(node);
        });
        // stateful edge store: the second ingest of the same anchor must FIND its
        // existing PART_OF edge (that is exactly the guard being regression-tested)
        Map<UUID, com.syllabai.knowledge.KnowledgeEdge> edges = new java.util.HashMap<>();
        when(knowledgeEdges.findBySourceIdAndRelationType(any(), any())).thenAnswer(inv ->
                Optional.ofNullable(edges.get(inv.getArgument(0, UUID.class))));
        when(knowledgeEdges.save(any(com.syllabai.knowledge.KnowledgeEdge.class)))
                .thenAnswer(inv -> {
                    com.syllabai.knowledge.KnowledgeEdge edge = inv.getArgument(0);
                    edges.put(edge.source().id(), edge);
                    return capture(edge);
                });
    }

    @Test
    @DisplayName("two papers of the same session share one anchor; papers do not collapse")
    void sameSessionPapersShareAnchorWithoutCollapsing() {
        Map<String, KnowledgeNode> anchors = new java.util.HashMap<>();
        inMemoryAnchors(anchors);

        service.ingest(sameSessionDraft("Paper 1C", "1c"), null);
        service.ingest(sameSessionDraft("Paper 2C", "2c"), null);

        long anchorSaves = savedAll.stream()
                .filter(e -> e instanceof KnowledgeNode
                        && ((KnowledgeNode) e).code().startsWith("ING-"))
                .count();
        assertThat(anchorSaves).isEqualTo(1);                       // find-or-create: saved once

        List<ExamPaper> papers = savedAll.stream()
                .filter(e -> e instanceof ExamPaper).map(e -> (ExamPaper) e).toList();
        assertThat(papers).hasSize(2);                              // distinct ExamPaper identities
        assertThat(papers).extracting(ExamPaper::unit)
                .containsExactly("Paper 1C", "Paper 2C");           // not collapsed into one

        KnowledgeNode anchor = anchors.get("ING-SUMMER2013");
        assertThat(anchor).isNotNull();
        // both papers' questions hang off the SAME placeholder anchor
        assertThat(savedAll.stream().filter(e -> e instanceof Question)
                .map(e -> ((Question) e).primaryTopicNodeId()))
                .containsOnly(anchor.id());
    }

    @Test
    @DisplayName("papers with printed paper codes derive distinct per-paper anchors")
    void distinctPaperCodesGetDistinctAnchors() {
        Map<String, KnowledgeNode> anchors = new java.util.HashMap<>();
        inMemoryAnchors(anchors);

        // drafts WITH a printed paper reference: anchor identity = the paper code itself
        PastPaperDraftDto d1 = draftWithCode("Paper 1C", "4CH0/1C", "1c");
        PastPaperDraftDto d2 = draftWithCode("Paper 2C", "4CH0/2C", "2c");
        service.ingest(d1, null);
        service.ingest(d2, null);

        assertThat(anchors).containsKeys("ING-4CH01CSUMMER2013", "ING-4CH02CSUMMER2013");
        assertThat(anchors.get("ING-4CH01CSUMMER2013").id())
                .isNotEqualTo(anchors.get("ING-4CH02CSUMMER2013").id());
    }

    /** same as {@link #sameSessionDraft} but with the printed paper reference present */
    private PastPaperDraftDto draftWithCode(String unit, String code, String docSuffix) {
        PastPaperDraftDto base = sameSessionDraft(unit, docSuffix);
        PastPaperDraftDto.PaperMeta m = base.paper();
        return new PastPaperDraftDto("1.0",
                new PastPaperDraftDto.PaperMeta(m.board(), m.qualification(), m.subject(),
                        m.unit(), m.sessionLabel(), code, m.questionPaperDocumentId(),
                        m.markSchemeDocumentId()),
                base.questions(), base.markScheme(), base.extractionMethod(),
                base.reviewRequired());
    }

    @Test
    @DisplayName("a found anchor keeps its original provenance; PART_OF edge not duplicated")
    void foundAnchorKeepsProvenanceAndEdgeNotDuplicated() {
        KnowledgeNode preExisting = TestIds.withId(new KnowledgeNode(
                "ING-SUMMER2013", NodeType.TOPIC, "Ingestion anchor: original",
                "original description", KnowledgeNode.ValidationStatus.UNVALIDATED,
                "original-provenance", "test-operator"), UUID.randomUUID());
        Map<String, KnowledgeNode> anchors = new java.util.HashMap<>();
        anchors.put(preExisting.code(), preExisting);
        inMemoryAnchors(anchors);

        // subject root exists → the service guards the PART_OF edge
        Subject subject = new Subject(null, "CHM", "Chemistry");
        UUID rootId = UUID.randomUUID();
        subject.linkKnowledgeNode(rootId);
        KnowledgeNode subjectRoot = TestIds.withId(new KnowledgeNode(
                "CHM", NodeType.SUBJECT, "Chemistry", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "seed", "test-operator"), rootId);
        when(subjects.findByCode(any())).thenReturn(Optional.of(subject));
        when(knowledgeNodes.findById(rootId)).thenReturn(Optional.of(subjectRoot));

        service.ingest(sameSessionDraft("Paper 1C", "1c"), null);
        service.ingest(sameSessionDraft("Paper 2C", "2c"), null);

        // pre-existing anchor reused untouched — no second save, provenance intact
        assertThat(anchors.get("ING-SUMMER2013")).isSameAs(preExisting);
        assertThat(savedAll.stream()
                .filter(e -> e instanceof KnowledgeNode
                        && ((KnowledgeNode) e).code().startsWith("ING-"))
                .count()).isZero();
        // exactly ONE PART_OF edge across both ingests (guard skips the duplicate)
        assertThat(savedAll.stream()
                .filter(e -> e instanceof com.syllabai.knowledge.KnowledgeEdge).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a nameless paper (no session label) is rejected — identity gate")
    void namelessPaperRejected() {
        PastPaperDraftDto nameless = new PastPaperDraftDto("1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry", "Paper 1C",
                        null, null, "qp-doc-x", "ms-doc-x"),
                draft().questions(),
                new PastPaperDraftDto.MarkSchemeDraft("1", "ms-doc-x", List.of()),
                "m", true);

        assertThatThrownBy(() -> service.ingest(nameless, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no session label");
    }

    @Test
    @DisplayName("paper title is built from printed identity and never contains 'null'")
    void paperTitleHasNoNullLiteral() {
        service.ingest(draft(), UUID.randomUUID());

        ExamPaper saved = (ExamPaper) savedAll.stream()
                .filter(e -> e instanceof ExamPaper).findFirst().orElseThrow();
        assertThat(saved.title()).doesNotContain("null");
        assertThat(saved.title()).contains("4CH0/1C").contains("January 2012");
    }
}
