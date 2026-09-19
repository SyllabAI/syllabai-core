package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.CanonicalDocumentValidator;
import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentEmbeddingService;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.content.InvalidDocumentException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (T-013): the content pipeline against a real pgvector Postgres —
 * canonical ingestion of the real 4CH0/1C Jan 2012 corpus (parser fixtures), verbatim
 * storage, deterministic chunking, idempotent re-ingest, deterministic fake embeddings
 * (no network in CI), cosine search with provenance, and the kind filter.
 *
 * <p>T-C07 (curriculum-scoped retrieval): every search now runs inside a
 * curriculum scope resolved through the REAL join path (documents → exam_papers
 * QP/MS document_id → subjects → curriculum_versions) — the positive control
 * serves scoped chunks, the negative controls prove a foreign-curriculum scope
 * sees nothing and an unlinked document (unresolvable curriculum) is never
 * served (fail-closed).</p>
 *
 * <p>Container state is shared across the class's test methods: the 4CH0 mark
 * scheme fixture is ingested by three of them, and ingestion is
 * checksum-idempotent. {@code fullPipeline} asserts the FIRST-ingestion
 * contract ({@code duplicate() == false}), so it is pinned to run first via
 * {@code @Order}; the other tests resolve to the already-stored document and
 * tolerate that.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContentPipelineIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** Deterministic bag-of-words hashing — search works offline, no API keys. */
    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        EmbeddingProvider fakeEmbeddingProvider() {
            return new HashingEmbeddingProvider();
        }
    }

    @Autowired
    private ContentIngestionService ingestion;
    @Autowired
    private DocumentEmbeddingService embedding;
    @Autowired
    private ContentRetrievalService retrieval;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private ExamPaperRepository examPapers;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Creates ACTIVE curriculum + subject + exam-paper rows linking
     * {@code documentId} as the paper's QP or MS document (the real T-C07 join
     * path), and returns the curriculum version id for scope construction.
     */
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

    private record Fixture(CanonicalDocumentDto dto, String raw) {
    }

    private static Fixture fixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/" + name);
        String raw = Files.readString(path);
        return new Fixture(JSON.readValue(raw, CanonicalDocumentDto.class), raw);
    }

    @Test
    @Order(1) // must ingest the shared 4CH0 fixture first — see the state note below
    @DisplayName("full pipeline: ingest → chunk → embed → cosine search with provenance")
    void fullPipeline() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");

        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.chunks()).isGreaterThan(0);
        assertThat(result.elements()).isEqualTo(ms.dto().totalElementCount());
        assertThat(result.pages()).isEqualTo(ms.dto().pageCount());

        // content-preserving storage: JSONB round-trips the canonical tree (the
        // SqlTypes.JSON writer normalizes formatting, so compare parsed trees —
        // the source checksum pins the original FILE, §8)
        Document stored = documents.findById(result.id()).orElseThrow();
        assertThat(JSON.readTree(stored.canonicalJson())).isEqualTo(JSON.readTree(ms.raw()));
        assertThat(stored.sourceEngine()).isEqualTo("opendataloader-pdf");
        assertThat(stored.sourceEngineVersion()).isEqualTo("2.5.7");

        // deterministic chunking: element coverage is total, no duplicates
        List<DocumentChunk> storedChunks = chunks
                .findByDocumentRowIdOrderByChunkIndexAsc(result.id());
        assertThat(storedChunks).hasSize(result.chunks());
        assertThat(storedChunks.stream().flatMap(c -> c.elementIds().stream()))
                .doesNotHaveDuplicates();

        // embed → search the Q7(a) halogens mark points
        DocumentEmbeddingService.EmbeddingResult embedded = embedding.embedDocument(result.id());
        assertThat(embedded.embedded()).isEqualTo(result.chunks());
        assertThat(embedded.model()).isEqualTo("fake-hashing");

        List<com.syllabai.content.ChunkHit> hits =
                retrieval.search("chlorine iodine astatine halogens", Document.Kind.MARK_SCHEME,
                        new CurriculumScope(linkPaper("4CH0-IT", ms.dto().documentId(),
                                Document.Kind.MARK_SCHEME), "4CH0-IT", Set.of()), 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).content()).containsIgnoringCase("Chlorine");
        assertThat(hits.get(0).elementIds()).isNotEmpty(); // citation provenance survives
        assertThat(hits.get(0).documentId()).isEqualTo(ms.dto().documentId());
        assertThat(hits.get(0).score()).isGreaterThan(0.0);

        // re-embedding is a no-op
        DocumentEmbeddingService.EmbeddingResult again = embedding.embedDocument(result.id());
        assertThat(again.embedded()).isZero();
        assertThat(again.skipped()).isEqualTo(result.chunks());
    }

    @Test
    @Order(2) // QP fixture — first ingestion here regardless of the MS tests' order
    @DisplayName("re-ingesting the same source is idempotent; kind filter excludes other kinds")
    void idempotentIngestAndKindFilter() throws Exception {
        Fixture qp = fixture("canonical-qp-4ch0-1c-jan2012.json");

        ContentIngestionService.IngestionResult first =
                ingestion.ingest(qp.dto(), qp.raw(), Document.Kind.QUESTION_PAPER, null);
        assertThat(first.duplicate()).isFalse();

        ContentIngestionService.IngestionResult second =
                ingestion.ingest(qp.dto(), qp.raw(), Document.Kind.QUESTION_PAPER, null);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(chunks.countByDocumentRowId(first.id()))
                .isEqualTo(first.chunks()); // no duplicate chunks

        embedding.embedDocument(first.id());
        UUID cvId = linkPaper("4CH0-IT", qp.dto().documentId(), Document.Kind.QUESTION_PAPER);
        CurriculumScope scope = new CurriculumScope(cvId, "4CH0-IT", Set.of());
        // kind filter: a MARK_SCHEME-scoped search never leaks QUESTION_PAPER chunks
        // (order-independent: vacuously true if no mark scheme is embedded yet)
        retrieval.search("Write your name here", Document.Kind.MARK_SCHEME, scope, 5)
                .forEach(h -> assertThat(h.kind()).isEqualTo("MARK_SCHEME"));
        // unfiltered, the top hit for QP text is the QP document itself
        var open = retrieval.search("Write your name here", null, scope, 5);
        assertThat(open).isNotEmpty();
        assertThat(open.get(0).documentId()).isEqualTo(qp.dto().documentId());
        assertThat(open.get(0).content()).containsIgnoringCase("name");
    }

    @Test
    @Order(3) // tolerates the MS fixture already being ingested (resolves to the same document)
    @DisplayName("T-C07 negative control: a foreign-curriculum scope never serves the corpus")
    void foreignCurriculumScopeServesNothing() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        embedding.embedDocument(result.id());

        // the owning curriculum serves the chunks...
        UUID owner = linkPaper("4CH0-IT", ms.dto().documentId(), Document.Kind.MARK_SCHEME);
        assertThat(retrieval.search("chlorine", null,
                new CurriculumScope(owner, "4CH0-IT", Set.of()), 5)).isNotEmpty();

        // ...a DIFFERENT ACTIVE curriculum (own subject, no papers) serves nothing
        UUID foreign = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "4CH1-IT", "IT fixture 4CH1",
                CurriculumVersion.Status.ACTIVE)).id();
        subjects.save(new Subject(curriculumVersions.findById(foreign).orElseThrow(),
                "4CH1-IT", "Chemistry (4CH1-IT)"));

        assertThat(retrieval.search("chlorine", null,
                new CurriculumScope(foreign, "4CH1-IT", Set.of()), 50)).isEmpty();
    }

    @Test
    @Order(4) // tolerates the MS fixture already being ingested (resolves to the same document)
    @DisplayName("T-C07 negative control: an unlinked document (unresolvable curriculum) is never served")
    void unlinkedDocumentNeverServed() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);
        embedding.embedDocument(result.id());

        // NO exam_papers row references this document — its curriculum is
        // unresolvable, so no scope may serve it (fail-closed, not a wildcard)
        UUID anyCv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "4CH0-UNLINKED", "IT fixture unlinked",
                CurriculumVersion.Status.ACTIVE)).id();
        subjects.save(new Subject(curriculumVersions.findById(anyCv).orElseThrow(),
                "4CH0-UNLINKED", "Chemistry (unlinked)"));

        assertThat(retrieval.search("chlorine", null,
                new CurriculumScope(anyCv, "4CH0-UNLINKED", Set.of()), 50)).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("a tampered canonical document is rejected with the full invariant list")
    void tamperedDocumentRejected() throws Exception {
        Fixture qp = fixture("canonical-qp-4ch0-1c-jan2012.json");
        CanonicalDocumentDto tampered = new CanonicalDocumentDto(
                qp.dto().documentId(), "0.9", qp.dto().version(), qp.dto().source(),
                qp.dto().pageCount(), qp.dto().pages(), qp.dto().sections(),
                qp.dto().textBlocks(), qp.dto().tables(), qp.dto().figures(),
                qp.dto().equations(), qp.dto().provenance(), null);

        assertThatThrownBy(() -> ingestion.ingest(tampered, qp.raw(), Document.Kind.OTHER, null))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @Order(6)
    @DisplayName("V33 atom-alignment: a grouped corpus chunks atom-aligned — no chunk crosses an atom")
    void atomAlignedChunking() {
        // the fixture carries a retrieval block whose subjectCode must resolve
        CurriculumVersion atomsCv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "NOTE-ATOMS", "IT fixture atoms",
                CurriculumVersion.Status.ACTIVE));
        subjects.save(new Subject(atomsCv, "NOTE-ATOMS", "Chemistry (atoms)"));

        String checksum = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String documentId = CanonicalDocumentValidator.derivedDocumentId(
                checksum, "opendataloader-pdf", "2.5.7");
        String filler = "lorem ".repeat(133); // ~800 chars ≈ 200 estimated tokens per block
        List<CanonicalDocumentDto.TextBlockElement> blocks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            blocks.add(new CanonicalDocumentDto.TextBlockElement("q3-" + i, "text_block",
                    1, null, "atom three part " + i + " " + filler, i, 1.0, "paragraph",
                    null, "opendataloader-pdf", "2.5.7", "q3"));
        }
        for (int i = 0; i < 3; i++) {
            blocks.add(new CanonicalDocumentDto.TextBlockElement("q4-" + i, "text_block",
                    2, null, "atom four part " + i + " " + filler, i, 1.0, "paragraph",
                    null, "opendataloader-pdf", "2.5.7", "q4"));
        }
        CanonicalDocumentDto doc = new CanonicalDocumentDto(documentId, "1.0", 1,
                new CanonicalDocumentDto.SourceInfo("atoms-it.pdf", checksum, "SHA-256",
                        "application/pdf", "atoms-it.pdf"),
                2, List.of(), List.of(), blocks, List.of(), List.of(), List.of(),
                new CanonicalDocumentDto.ProvenanceInfo("opendataloader-pdf", "2.5.7",
                        "2026-09-20T00:00:00Z", java.util.Map.of(), "syllabai-parser", "1.0"),
                new CanonicalDocumentDto.RetrievalMeta("IGCSE Chemistry", "NOTE-ATOMS",
                        "JUN", 2022, "1C", null, null, null));

        // 200-token blocks pack against the 300-token target → each atom yields
        // MULTIPLE chunks; the assertion is that no chunk ever mixes atoms
        ContentIngestionService.IngestionResult result =
                ingestion.ingest(doc, "{}", Document.Kind.QUESTION_PAPER, null);
        assertThat(result.chunks()).isGreaterThanOrEqualTo(4);

        List<DocumentChunk> stored = chunks
                .findByDocumentRowIdOrderByChunkIndexAsc(result.id());
        assertThat(stored).hasSize(result.chunks());
        for (DocumentChunk chunk : stored) {
            String first = chunk.elementIds().get(0);
            boolean atomThree = first.startsWith("q3-");
            assertThat(chunk.atomNumber()).isEqualTo(atomThree ? "3" : "4");
            for (String id : chunk.elementIds()) {
                assertThat(id.startsWith(atomThree ? "q3-" : "q4-")).isTrue();
            }
            // metadata mirror + per-chunk header on EVERY chunk (chunks 2..n of an
            // atom are not blind — plan §4.1 step 4)
            assertThat(chunk.series()).isEqualTo("JUN");
            assertThat(chunk.year()).isEqualTo(2022);
            assertThat(chunk.paperCode()).isEqualTo("1C");
            assertThat(chunk.embedRev()).isEqualTo(ChunkVectorRepository.CURRENT_EMBED_REV);
            assertThat(chunk.content()).contains("IGCSE Chemistry 4CH1 | Jun 2022 | 1C | Q");
        }
    }

    @Test
    @Order(7)
    @DisplayName("V33 subject branch: paper-less note chunks serve inside their curriculum and leak nowhere else")
    void subjectLinkedNoteServesViaSubjectBranch() {
        // curriculum + subject with NO exam-paper row (the knowledge layer shape)
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "NOTE-IT", "IT fixture notes",
                CurriculumVersion.Status.ACTIVE));
        subjects.save(new Subject(cv, "NOTE-IT", "Chemistry (notes)"));

        String checksum = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String documentId = CanonicalDocumentValidator.derivedDocumentId(
                checksum, "opendataloader-pdf", "2.5.7");
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                new CanonicalDocumentDto.TextBlockElement("n-0", "text_block", 1, null,
                        "Electrolysis of molten sodium chloride produces sodium metal and "
                                + "chlorine gas at the electrodes.", 0, 1.0, "paragraph",
                        null, "opendataloader-pdf", "2.5.7", null),
                new CanonicalDocumentDto.TextBlockElement("n-1", "text_block", 1, null,
                        "Molten ionic compounds conduct because their ions are free to "
                                + "move towards the electrodes.", 1, 1.0, "paragraph",
                        null, "opendataloader-pdf", "2.5.7", null));
        CanonicalDocumentDto note = new CanonicalDocumentDto(documentId, "1.0", 1,
                new CanonicalDocumentDto.SourceInfo("notes.pdf", checksum, "SHA-256",
                        "application/pdf", "notes.pdf"),
                1, List.of(), List.of(), blocks, List.of(), List.of(), List.of(),
                new CanonicalDocumentDto.ProvenanceInfo("opendataloader-pdf", "2.5.7",
                        "2026-09-20T00:00:00Z", java.util.Map.of(), "syllabai-parser", "1.0"),
                new CanonicalDocumentDto.RetrievalMeta("IGCSE Chemistry", "NOTE-IT",
                        null, null, null, "Notes", "Electrolysis", List.of("1.44")));

        ContentIngestionService.IngestionResult result =
                ingestion.ingest(note, "{}", Document.Kind.EXTERNAL_NOTES, null);
        embedding.embedDocument(result.id());

        // the owning curriculum serves the note chunk through the SUBJECT branch
        // (there is no exam_papers row — the old join path alone could never see it)
        var owner = retrieval.search("electrolysis molten chloride electrodes",
                null, new CurriculumScope(cv.id(), "NOTE-IT", Set.of()), 10);
        assertThat(owner).isNotEmpty();
        assertThat(owner.get(0).documentId()).isEqualTo(documentId);
        assertThat(owner.get(0).content()).containsIgnoringCase("electrolysis");

        // leakage control: a foreign curriculum (own subject, different id) never
        // sees the note chunk — fail-closed unchanged
        var foreignHits = retrieval.search("electrolysis molten chloride electrodes",
                null, new CurriculumScope(
                        curriculumVersions.save(new CurriculumVersion("Edexcel", "IGCSE",
                                "NOTE-FOREIGN", "IT fixture foreign",
                                CurriculumVersion.Status.ACTIVE)).id(),
                        "NOTE-FOREIGN", Set.of()), 50);
        assertThat(foreignHits)
                .noneMatch(h -> h.documentId().equals(documentId));
    }

    /**
     * Deterministic offline double: lowercase word → positive hash mod 768, L2-normalized.
     * Real embeddings are Gemini's (see DocumentEmbeddingService); this one only needs
     * to make shared words imply cosine similarity — enough to exercise the pgvector
     * operators end-to-end without network access in CI.
     */
    static final class HashingEmbeddingProvider implements EmbeddingProvider {

        @Override
        public String model() {
            return "fake-hashing";
        }

        @Override
        public int dimension() {
            return 768;
        }

        @Override
        public float[] embedDocument(String text) {
            return embed(text);
        }

        @Override
        public float[] embedQuery(String text) {
            return embed(text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        private float[] embed(String text) {
            float[] vector = new float[768];
            for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
                if (word.isBlank()) {
                    continue;
                }
                int dim = Math.floorMod(word.hashCode(), 768);
                vector[dim] += 1f;
            }
            double norm = 0;
            for (float v : vector) {
                norm += v * v;
            }
            if (norm > 0) {
                float scale = (float) (1.0 / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) {
                    vector[i] *= scale;
                }
            } else {
                vector[0] = 1f;
            }
            return vector;
        }
    }
}
