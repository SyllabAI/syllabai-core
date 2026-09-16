package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ChunkHit;
import com.syllabai.content.ChunkLexicalRepository;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (T-C14): the lexical (BM25-style) search surface against a
 * real Postgres — the V28 generated tsvector column and GIN index exist
 * (flyway), {@code websearch_to_tsquery} parses learner phrasing,
 * {@code ts_rank_cd} ranks a verbatim-stem query's own chunk into the top-5
 * (the run-001 R1 population shape), the T-C07 curriculum predicate excludes a
 * foreign-curriculum scope and an unlinked document (fail-closed), and blank
 * queries return empty without error.
 *
 * <p>Unit-level SQL shape is verified by {@code ChunkLexicalRepositoryTest};
 * this class proves the row-level behavior. Docker-gated — runs on the CI lane
 * (workflow_dispatch), never in the agent sandbox.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChunkLexicalSearchIT {

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
    private ChunkLexicalRepository lexical;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private ExamPaperRepository examPapers;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Fixture(CanonicalDocumentDto dto, String raw) {
    }

    private static Fixture fixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/" + name);
        String raw = Files.readString(path);
        return new Fixture(JSON.readValue(raw, CanonicalDocumentDto.class), raw);
    }

    private UUID linkPaper(String code, String documentId, Document.Kind kind) {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", code, "IT fixture " + code,
                CurriculumVersion.Status.ACTIVE));
        Subject subject = subjects.save(new Subject(cv, code, "Chemistry (" + code + ")"));
        examPapers.save(new ExamPaper(subject.id(), "IT paper " + code, "Edexcel", "IGCSE",
                null, null, code + "/1C",
                kind == Document.Kind.QUESTION_PAPER ? documentId : null,
                kind == Document.Kind.MARK_SCHEME ? documentId : null,
                ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        return cv.id();
    }

    @Test
    @Order(1)
    @DisplayName("V28 column exists; stem-verbatim query ranks its own chunk top-5; provenance survives")
    void rankingSanity() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        assertThat(result.chunks()).isGreaterThan(0);

        UUID cvId = linkPaper("4CH1-IT", ms.dto().documentId(), Document.Kind.MARK_SCHEME);
        CurriculumScope scope = new CurriculumScope(cvId, "4CH1-IT", Set.of());

        // the MS is a real QP/MS corpus fixture — a distinctive stem phrase from
        // its own content must retrieve its own chunk inside the top-5 (the R1
        // population shape run-001 measured at 0.95 recall@5 ALL-chunks).
        // Query premise is fixture-verified (run 35157930089): the Jan-2012 MS
        // Q7a answer table (element e000026, page 16) reads "Chlorine / Cl2
        // Iodine / I2 Astatine / At2" — all three terms co-occur in ONE element
        // and the chunker never splits an element, so this AND-tsquery has a
        // guaranteed in-fixture target. The earlier "… halogens" phrasing was
        // unsatisfiable by construction: no halogen token exists anywhere in the
        // fixture.
        List<ChunkHit> hits = lexical.search("chlorine iodine astatine", null,
                scope.curriculumVersionId(), 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.stream().limit(5).map(ChunkHit::content))
                .anySatisfy(content -> assertThat(content).containsIgnoringCase("Chlorine"));
        assertThat(hits.get(0).documentId()).isEqualTo(ms.dto().documentId());
        assertThat(hits.get(0).chunkId()).isNotNull();
        assertThat(hits.get(0).score()).isGreaterThan(0.0); // ts_rank_cd native score
        assertThat(hits.get(0).embeddingModel()).isNull(); // lexical text arm

        // deterministic ordering: same query, same database state → identical ranking
        List<ChunkHit> again = lexical.search("chlorine iodine astatine halogens", null,
                scope.curriculumVersionId(), 5);
        assertThat(again).extracting(ChunkHit::chunkId).isEqualTo(hits.stream().map(ChunkHit::chunkId).toList());
    }

    @Test
    @Order(2)
    @DisplayName("T-C07 negative controls: foreign-curriculum scope and unlinked document see nothing")
    void curriculumNegativeControls() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        UUID own = linkPaper("4CH1-ITB", ms.dto().documentId(), Document.Kind.MARK_SCHEME);
        // a foreign-curriculum paper whose linked document ids match nothing real
        UUID foreign = linkPaper("WCH11-IT", "unlinked-sentinel-" + System.nanoTime(),
                Document.Kind.QUESTION_PAPER);

        assertThat(lexical.search("chlorine", null, own, 10)).isNotEmpty();
        // foreign curriculum scope → nothing (fail-closed, never unscoped)
        assertThat(lexical.search("chlorine", null, foreign, 10)).isEmpty();
        // a document ingested but linked to NO paper resolves to no curriculum and is never served
        Fixture orphan = fixture("canonical-qp-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult orphanResult =
                ingestion.ingest(orphan.dto(), orphan.raw(), Document.Kind.QUESTION_PAPER, null);
        assertThat(orphanResult.duplicate()).isFalse();
        assertThat(lexical.search("chlorine", null, own, 50))
                .noneSatisfy(h -> assertThat(h.documentId()).isEqualTo(orphan.dto().documentId()));
    }

    @Test
    @Order(3)
    @DisplayName("blank query fails closed to empty; null scope rejected before SQL")
    void failClosedContracts() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        UUID cvId = linkPaper("4CH1-ITC", ms.dto().documentId(), Document.Kind.MARK_SCHEME);

        assertThat(lexical.search("", null, cvId, 5)).isEmpty();
        assertThat(lexical.search("   ", null, cvId, 5)).isEmpty();
        // In this full Spring context the @Repository bean is wrapped by
        // PersistenceExceptionTranslationInterceptor: the guard's raw
        // IllegalArgumentException is translated — per the JPA spec's
        // programming-error mapping (EntityManagerFactoryUtils maps IAE/ISE to
        // InvalidDataAccessApiUsageException) — to InvalidDataAccessApiUsageException
        // carrying the SAME message with the IAE as cause (run 35157930089
        // evidence). The unit layer (ChunkLexicalRepositoryTest) asserts the raw
        // type: no proxy exists there. Same guard, two observable layers.
        assertThatThrownBy(() -> lexical.search("chlorine", null, null, 5))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("never runs unscoped")
                .getCause().isInstanceOf(IllegalArgumentException.class);
    }
}
