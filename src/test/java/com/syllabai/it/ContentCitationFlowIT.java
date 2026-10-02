package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.CanonicalDocumentValidator;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.ContentReaderController;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.shared.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the learner-facing citation read surface (L5) against a
 * real Postgres. Tutor citations deep-link to
 * {@code GET /api/v1/content/documents/{id}?page=N} — this IT pins that the
 * link target is honest for every role: a document on the serving side of the
 * corpus law renders its header and verbatim page text, while a corpus import
 * nothing serves (SUGGESTED, no validated paper) is an INDISTINGUISHABLE 404 —
 * the T-C20 validation gate carried over to the drill-in, never a state leak.
 *
 * <p>Both gate branches are pinned separately: the paper branch (a VALIDATED
 * exam paper whose question paper / mark scheme document_id matches) and the
 * knowledge-layer branch (the document row itself VALIDATED — no paper row
 * involved). The page text contract (verbatim canonical elements in reading
 * order) is unit-pinned by DocumentPageTextTest; here it is asserted through
 * the full stack: JSONB → parse → extract → view.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ContentCitationFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ContentReaderController controller;
    @Autowired
    private ContentIngestionService ingestion;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** the controller is method-gated (M5 defense-in-depth) — direct bean
     *  calls still need an Authentication, same as AssignmentFlowIT */
    private void loginAsStudent() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
    }

    private record Fixture(CanonicalDocumentDto dto, String raw) {
    }

    private Fixture fixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/" + name);
        String raw = Files.readString(path);
        return new Fixture(JSON.readValue(raw, CanonicalDocumentDto.class), raw);
    }

    /**
     * Creates ACTIVE curriculum + subject + exam-paper rows linking
     * {@code documentId} as the paper's QP or MS document (the real T-C07
     * join path), pins the paper VALIDATED (the exact gate the citation view
     * mirrors), and returns nothing — the caller holds the document row.
     */
    private void linkValidatedPaper(String code, String documentId, Document.Kind kind) {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", code, "IT fixture " + code,
                CurriculumVersion.Status.ACTIVE));
        Subject subject = subjects.save(new Subject(cv, code, "Chemistry (" + code + ")"));
        ExamPaper paper = examPapers.save(new ExamPaper(subject.id(), "IT paper " + code,
                "Edexcel", "IGCSE",
                null, null, code + "/1C",
                kind == Document.Kind.QUESTION_PAPER ? documentId : null,
                kind == Document.Kind.MARK_SCHEME ? documentId : null,
                ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        jdbc.update("update exam_papers set validation_state = 'VALIDATED' where id = ?",
                paper.id());
    }

    @Test
    @DisplayName("a corpus import nothing serves is an honest 404 — header, page and unknown id alike")
    void suggestedCorpusImportIsAnHonest404() throws Exception {
        loginAsStudent();
        Fixture qp = fixture("canonical-qp-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(qp.dto(), qp.raw(), Document.Kind.QUESTION_PAPER, null);
        UUID row = result.id();
        assertThat(documents.findById(row)).isPresent(); // the row EXISTS — the gate refuses it

        assertThatThrownBy(() -> controller.get(row, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.get(row, 1))
                .isInstanceOf(NotFoundException.class);
        // indistinguishable from a row that does not exist at all — no state leak
        assertThatThrownBy(() -> controller.get(UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a VALIDATED paper's mark scheme renders header + verbatim page text; bounds hold")
    void validatedPaperUnlocksHeaderAndPageText() throws Exception {
        loginAsStudent();
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        UUID row = result.id();
        linkValidatedPaper("4CH0-CTA", ms.dto().documentId(), Document.Kind.MARK_SCHEME);

        ContentReaderController.CitationDocumentView header = controller.get(row, null);
        assertThat(header.id()).isEqualTo(row);
        assertThat(header.documentId()).isEqualTo(ms.dto().documentId());
        assertThat(header.kind()).isEqualTo("MARK_SCHEME");
        assertThat(header.title()).isEqualTo("input.pdf"); // fileName (the summary title law)
        assertThat(header.pageCount()).isEqualTo(ms.dto().pageCount());
        assertThat(header.page()).isNull(); // header shape: no page, no text
        assertThat(header.text()).isNull();

        // page 16 carries the Q7(a) halogens mark points in the shared fixture
        ContentReaderController.CitationDocumentView page = controller.get(row, 16);
        assertThat(page.page()).isEqualTo(16);
        assertThat(page.text()).isNotBlank();
        assertThat(page.text()).contains("Chlorine");
        assertThat(page.title()).isEqualTo("input.pdf"); // identity fields ride along

        // page bounds are honest 404s, never clamped or empty-200s
        assertThatThrownBy(() -> controller.get(row, 0))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.get(row, ms.dto().pageCount() + 1))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("knowledge-layer branch: the document row itself VALIDATED needs no paper")
    void validatedKnowledgeLayerDocumentNeedsNoPaper() throws Exception {
        loginAsStudent();
        // minimal hand-built canonical document (the public derivation mints a
        // valid id — a test-local derivation would be the drift the mirror prevents)
        String checksum = "it-citation-knowledge-" + UUID.randomUUID();
        String engine = "it-fixture";
        String engineVersion = "1";
        CanonicalDocumentDto dto = new CanonicalDocumentDto(
                CanonicalDocumentValidator.derivedDocumentId(checksum, engine, engineVersion),
                "1.0", 1,
                new CanonicalDocumentDto.SourceInfo("it://citation-knowledge", checksum,
                        "SHA-256", "application/pdf", "it-knowledge.pdf"),
                1, List.of(new CanonicalDocumentDto.PageInfo(1, 100.0, 100.0)),
                List.of(),
                List.of(new CanonicalDocumentDto.TextBlockElement("kb-1", "text", 1, null,
                        "knowledge layer page text", 1, 0.99, null, null, engine,
                        engineVersion, null)),
                List.of(), List.of(),
                new CanonicalDocumentDto.ProvenanceInfo(engine, engineVersion,
                        "2026-10-02T00:00:00Z", null, null, "1.0"),
                null);

        ContentIngestionService.IngestionResult result =
                ingestion.ingest(dto, JSON.writeValueAsString(dto), Document.Kind.TEXTBOOK,
                        null);
        UUID row = result.id();

        // SUGGESTED with no paper row: refused, exactly like any unserved import
        assertThatThrownBy(() -> controller.get(row, null))
                .isInstanceOf(NotFoundException.class);

        // the knowledge-layer flip (V29 corpus law: human validation on the row)
        jdbc.update("update documents set validation_state = 'VALIDATED' where id = ?", row);

        ContentReaderController.CitationDocumentView header = controller.get(row, null);
        assertThat(header.kind()).isEqualTo("TEXTBOOK");
        assertThat(header.title()).isEqualTo("it-knowledge.pdf");
        ContentReaderController.CitationDocumentView page = controller.get(row, 1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.text()).isEqualTo("knowledge layer page text");
    }
}
