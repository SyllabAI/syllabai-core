package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provenance read-model: the ingestion-time source identity of the QP/MS
 * pair (document ids, file names, source URIs and the checksums that pin the
 * original files) is exposed to teachers read-only, fail-closed when a paper
 * or either of its source documents is missing, and never carries content
 * payloads (the review surface stays the answer-key boundary).
 */
class ContentControllerProvenanceTest {

    private final PastPaperIngestionService ingestion = mock(PastPaperIngestionService.class);
    private final ContentReviewService review = mock(ContentReviewService.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ContentController controller = new ContentController(
            ingestion, review, examPapers, questionVersions, markSchemes, documents);

    private static Document document(String documentId, String fileName, String checksum) {
        // the read model only touches accessors, so a minimal real instance is fine
        return new Document(documentId, "1.0", 1, null, null, fileName,
                "application/pdf", checksum, "SHA-256", 0, 0,
                0, 0, null, null, null, null, null);
    }

    @Test
    @DisplayName("provenance view exposes QP/MS source identities, nothing else")
    void provenanceView() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = org.mockito.Mockito.mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.questionPaperDocumentId()).thenReturn("qp-doc-id");
        when(paper.markSchemeDocumentId()).thenReturn("ms-doc-id");
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-id"))
                .thenReturn(Optional.of(document("qp-doc-id", "June 2019 QP.pdf", "qp-checksum")));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("ms-doc-id"))
                .thenReturn(Optional.of(document("ms-doc-id", "June 2019 MS.pdf", "ms-checksum")));

        ContentController.PaperProvenanceView view = controller.paperProvenance(paperId);

        assertThat(view.paperId()).isEqualTo(paperId);
        assertThat(view.questionPaper().documentId()).isEqualTo("qp-doc-id");
        assertThat(view.questionPaper().fileName()).isEqualTo("June 2019 QP.pdf");
        assertThat(view.questionPaper().checksum()).isEqualTo("qp-checksum");
        assertThat(view.questionPaper().checksumAlgorithm()).isEqualTo("SHA-256");
        assertThat(view.markScheme().documentId()).isEqualTo("ms-doc-id");
        assertThat(view.markScheme().fileName()).isEqualTo("June 2019 MS.pdf");
        assertThat(view.markScheme().checksum()).isEqualTo("ms-checksum");
    }

    @Test
    @DisplayName("fail-closed: a paper without source document ids has no provenance (404)")
    void provenanceMissingDocumentIds() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = org.mockito.Mockito.mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.questionPaperDocumentId()).thenReturn(null);
        when(paper.markSchemeDocumentId()).thenReturn("ms-doc-id");
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> controller.paperProvenance(paperId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("provenance");
    }

    @Test
    @DisplayName("fail-closed: a paper whose source document row is missing (404)")
    void provenanceMissingDocumentRow() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = org.mockito.Mockito.mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.questionPaperDocumentId()).thenReturn("qp-doc-id");
        when(paper.markSchemeDocumentId()).thenReturn("ms-doc-id");
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-id"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.paperProvenance(paperId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("provenance");
    }
}
