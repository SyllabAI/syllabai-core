package com.syllabai.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher content endpoints for the canonical document store (T-013). Route security:
 * {@code /api/v1/teacher/**} requires TEACHER/ADMIN. The canonical JSON is received
 * as-is and stored in JSONB (content-preserving; the source checksum pins the
 * original file per §8).
 */
@RestController
@RequestMapping("/api/v1/teacher/content/documents")
public class ContentDocumentController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContentIngestionService ingestion;
    private final DocumentEmbeddingService embedding;
    private final ContentRetrievalService retrieval;
    private final DocumentRepository documents;
    private final CurriculumScopeResolver curriculumScopes;

    public ContentDocumentController(ContentIngestionService ingestion,
                                     DocumentEmbeddingService embedding,
                                     ContentRetrievalService retrieval,
                                     DocumentRepository documents,
                                     CurriculumScopeResolver curriculumScopes) {
        this.ingestion = ingestion;
        this.embedding = embedding;
        this.retrieval = retrieval;
        this.documents = documents;
        this.curriculumScopes = curriculumScopes;
    }

    /** ingest a syllabai-parser canonical document (schema 1.0) — chunks land un-embedded */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionView ingest(@CurrentUserId UUID ingestedBy,
                                @RequestParam(defaultValue = "OTHER") Document.Kind kind,
                                @RequestBody String rawJson) {
        CanonicalDocumentDto doc = parse(rawJson);
        ContentIngestionService.IngestionResult result = ingestion.ingest(doc, rawJson, kind,
                ingestedBy);
        return new IngestionView(result.id(), result.documentId(), result.duplicate(),
                result.chunks(), result.elements(), result.pages(), kind.name());
    }

    @GetMapping
    public List<DocumentSummaryView> list() {
        return documents.findAllByOrderByCreatedAtDesc().stream()
                .map(DocumentSummaryView::from)
                .toList();
    }

    @GetMapping("/{id}")
    public DocumentSummaryView get(@PathVariable UUID id) {
        return documents.findById(id)
                .map(DocumentSummaryView::from)
                .orElseThrow(() -> new NotFoundException("Document", id));
    }

    /** the sealed canonical JSON exactly as ingested (ops/debug + citation fetches) */
    @GetMapping(value = "/{id}/canonical", produces = MediaType.APPLICATION_JSON_VALUE)
    public String canonical(@PathVariable UUID id) {
        return documents.findById(id)
                .map(Document::canonicalJson)
                .orElseThrow(() -> new NotFoundException("Document", id));
    }

    /** embed all pending chunks — idempotent, resumable, no failover by design */
    @PostMapping("/{id}/embed")
    public EmbeddingView embed(@PathVariable UUID id) {
        DocumentEmbeddingService.EmbeddingResult result = embedding.embedDocument(id);
        return new EmbeddingView(result.documentRowId(), result.documentId(), result.model(),
                result.embedded(), result.skipped(), result.totalChunks());
    }

    /**
     * Vector search over the chunk index — content-side retrieval verification for
     * teachers/ops; the learner-facing KA-RAG surface (T-024/T-025) builds on the
     * same service, not on this endpoint. Curriculum-scoped like every serving
     * path (T-C07): an unresolved active curriculum yields an empty result —
     * never an unscoped search.
     */
    @GetMapping("/search")
    public List<ChunkHitView> search(@CurrentUserId UUID requesterId,
                                     @RequestParam @NotBlank String query,
                                     @RequestParam(required = false) Document.Kind kind,
                                     @RequestParam(defaultValue = "10") int limit) {
        return curriculumScopes.resolveActive(requesterId)
                .map(scope -> retrieval.search(query, kind, scope, limit))
                .orElse(List.of())
                .stream()
                .map(ChunkHitView::from)
                .toList();
    }

    private CanonicalDocumentDto parse(String rawJson) {
        try {
            return JSON.readValue(rawJson, CanonicalDocumentDto.class);
        } catch (Exception e) {
            throw new InvalidDocumentException(
                    "request body is not a canonical document (schema 1.0): " + e.getMessage());
        }
    }

    // ── views ───────────────────────────────────────────────────────────────

    public record IngestionView(UUID id, String documentId, boolean duplicate, int chunks,
                                int elements, int pages, String kind) {
    }

    public record DocumentSummaryView(UUID id, String documentId, int docVersion, String kind,
                                      String title, int pageCount, int elementCount,
                                      int textElementCount, int chunkCount, String sourceEngine,
                                      String sourceEngineVersion, String checksum,
                                      String createdAt) {

        static DocumentSummaryView from(Document d) {
            return new DocumentSummaryView(d.id(), d.documentId(), d.docVersion(),
                    d.kind().name(), d.fileName() == null ? d.sourceUri() : d.fileName(),
                    d.pageCount(), d.elementCount(), d.textElementCount(), d.chunkCount(),
                    d.sourceEngine(), d.sourceEngineVersion(), d.checksum(),
                    d.createdAt().toString());
        }
    }

    public record EmbeddingView(UUID id, String documentId, String model, int embedded,
                                int skipped, int totalChunks) {
    }

    public record ChunkHitView(UUID chunkId, String documentId, String kind, int chunkIndex,
                               String content, Integer pageStart, Integer pageEnd,
                               List<String> elementIds, String embeddingModel, double score) {

        static ChunkHitView from(ChunkHit h) {
            return new ChunkHitView(h.chunkId(), h.documentId(), h.kind(), h.chunkIndex(),
                    h.content(), h.pageStart(), h.pageEnd(), h.elementIds(),
                    h.embeddingModel(), h.score());
        }
    }
}
