package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.retrieval.Bm25Retriever;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-C05 validation-boundary controls for the lexical arm (T-C14), end-to-end
 * through the production provider ({@link Bm25Retriever}):
 *
 * <ul>
 *   <li>a SUGGESTED-only paper's chunks are NEVER served, however strong the
 *       lexical match — and flipping the paper to VALIDATED flips availability,
 *       proving the exclusion is the boundary predicate, not missing data;</li>
 *   <li>flagging a VALIDATED paper gates its chunks again;</li>
 *   <li>the serving-eligible overload rejects a null scope before any SQL;</li>
 *   <li>no scope-free lexical surface exists at the contract level.</li>
 * </ul>
 *
 * The T-C07 negative controls (foreign curriculum, unlinked document, blank
 * query) live in {@code ChunkLexicalSearchIT} and are not duplicated here; this
 * class proves the boundary the fabric records as "enforced centrally, later" —
 * the arm is compliant by construction NOW.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class LexicalBoundaryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private ContentIngestionService ingestion;
    @Autowired
    private Bm25Retriever bm25;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Fixture(CanonicalDocumentDto dto, String raw) {
    }

    private static Fixture fixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/" + name);
        String raw = Files.readString(path);
        return new Fixture(JSON.readValue(raw, CanonicalDocumentDto.class), raw);
    }

    /** Fixture-verified term: the 4CH0/1C Jan 2012 MS carries halogen content. */
    private static final String QUERY = "chlorine iodine astatine";

    private UUID linkPaper(String code, String documentId, Document.Kind kind) {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", code, "IT fixture " + code,
                CurriculumVersion.Status.ACTIVE));
        Subject subject = subjects.save(new Subject(cv, code, "Chemistry (" + code + ")"));
        ExamPaper paper = examPapers.save(new ExamPaper(subject.id(), "IT paper " + code, "Edexcel", "IGCSE",
                null, null, code + "/1C",
                kind == Document.Kind.QUESTION_PAPER ? documentId : null,
                kind == Document.Kind.MARK_SCHEME ? documentId : null,
                ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        cvId = cv.id();
        return paper.id();
    }

    private UUID cvId;

    @Test
    @DisplayName("SUGGESTED-only paper is never served; VALIDATED flips availability; FLAGGED gates again")
    void suggestedNeverServedUntilValidated() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        assertThat(result.chunks()).isGreaterThan(0);
        UUID paperId = linkPaper("4CH1-BND", ms.dto().documentId(), Document.Kind.MARK_SCHEME);
        ExamPaper paper = examPapers.findById(paperId).orElseThrow();

        CurriculumScope scope = new CurriculumScope(cvId, "4CH1-BND", Set.of());

        // SUGGESTED (paper default): zero candidates however well the content matches
        List<RetrievalCandidate> suggested =
                bm25.retrieve(StructuredRetrievalQuery.of(QUERY, scope, 20));
        assertThat(suggested).isEmpty();

        // VALIDATED: the SAME content becomes reachable — the predicate is the gate
        paper.validate();
        examPapers.save(paper);
        List<RetrievalCandidate> validated =
                bm25.retrieve(StructuredRetrievalQuery.of(QUERY, scope, 20));
        assertThat(validated).isNotEmpty();
        assertThat(validated).allSatisfy(c ->
                assertThat(c.documentId()).isEqualTo(ms.dto().documentId()));

        // FLAGGED: gated again (a flagged paper gates everything under it)
        paper.flag();
        examPapers.save(paper);
        assertThat(bm25.retrieve(StructuredRetrievalQuery.of(QUERY, scope, 20))).isEmpty();
    }

    @Test
    @DisplayName("no scope-free lexical surface exists: the contract rejects a null scope")
    void nullScopeRejectedAtTheContract() {
        // the fabric contract itself carries the T-C07 invariant
        assertThatThrownBy(() -> StructuredRetrievalQuery.of(QUERY, null, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("never runs unscoped");
    }
}
