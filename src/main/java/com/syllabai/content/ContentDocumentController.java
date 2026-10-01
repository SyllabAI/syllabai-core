package com.syllabai.content;

import org.springframework.security.access.prepost.PreAuthorize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
// deep-audit 09-28 M5: method-level role check mirroring the route rule
// in SecurityConfig (defense in depth — the route matchers stay authoritative;
// a future route change that drops the matcher still hits this layer)
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ContentDocumentController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(ContentDocumentController.class);

    /** T-C31: additive machine-readable empty-cause header — the JSON body stays a bare array. */
    static final String EMPTY_CAUSE_HEADER = "X-Search-Empty-Cause";

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
     *
     * <p>T-C31 serving-emptiness observability: on an EMPTY result only, the
     * response carries the additive {@link #EMPTY_CAUSE_HEADER} header naming
     * WHY it is empty ({@link SearchEmptyCause} — the T-C23 fix: all three
     * empty-causes used to return the identical {@code 200 + []}) and a
     * structured log line records the stage funnel. The JSON body stays a bare
     * array on every path — wire-compatible with all existing consumers — and
     * the happy path runs exactly the queries it ran before.</p>
     *
     * <p>ADR-030 follow-through — course-aware search: an OPTIONAL {@code courseRef}
     * (the same opaque hub-supplied reference the tutor ask carries since V53)
     * switches the resolution from the global {@code resolveActive} singleton to
     * {@code resolveForCourse} — exact ACTIVE code match plus the same
     * owns-surface test, exactly one owner serves. A ref that names zero or
     * ambiguous owners yields {@code 200 + []} labelled
     * {@link SearchEmptyCause#COURSE_REF_UNRESOLVED} with NO retrieval run and NO
     * fallback to the global scope (a wrong-corpus answer is worse than an empty
     * one). A blank or absent ref keeps the legacy path byte-identical — pilot
     * compatibility is a design decision, not a fallback.</p>
     */
    @GetMapping("/search")
    public ResponseEntity<List<ChunkHitView>> search(@CurrentUserId UUID requesterId,
                                     @RequestParam @NotBlank String query,
                                     @RequestParam(required = false) Document.Kind kind,
                                     @RequestParam(defaultValue = "10") int limit,
                                     @RequestParam(required = false) String courseRef) {
        boolean courseTagged = courseRef != null && !courseRef.isBlank();
        var scope = courseTagged
                ? curriculumScopes.resolveForCourse(courseRef.strip())
                : curriculumScopes.resolveActive(requesterId);
        if (scope.isEmpty()) {
            SearchEmptyCause cause = courseTagged
                    ? SearchEmptyCause.COURSE_REF_UNRESOLVED
                    : SearchEmptyCause.SCOPE_UNRESOLVED;
            LOG.info("search_empty cause={} requester={}{}", cause, requesterId,
                    courseTagged ? " ref=" + describeRef(courseRef) : "");
            return ResponseEntity.ok()
                    .header(EMPTY_CAUSE_HEADER, cause.name())
                    .body(List.of());
        }
        List<ChunkHit> hits = retrieval.search(query, kind, scope.get(), limit);
        if (!hits.isEmpty()) {
            return ResponseEntity.ok().body(hits.stream().map(ChunkHitView::from).toList());
        }
        SearchEmptyDiagnostics diagnostics = retrieval.diagnoseEmpty(kind, scope.get());
        SearchEmptyCause cause = diagnostics.cause();
        LOG.info("search_empty cause={} kind={} chunksInScope={} embeddedInScope={} "
                        + "inScopeAtRev={} servingEligible={}",
                cause, kind, diagnostics.chunksInScope(), diagnostics.embeddedInScope(),
                diagnostics.inScopeAtRev(), diagnostics.servingEligible());
        return ResponseEntity.ok()
                .header(EMPTY_CAUSE_HEADER, cause.name())
                .body(List.of());
    }

    /** Log-safe rendering of the caller-supplied ref: control characters
     *  flattened and hard-capped at the course_ref width — an untrusted wire
     *  value never writes a newline into the serving log. */
    private static String describeRef(String courseRef) {
        String ref = courseRef.strip();
        if (ref.length() > 64) {
            ref = ref.substring(0, 64) + "...";
        }
        return ref.replaceAll("[\\x00-\\x1f\\x7f]", "?");
    }

    private CanonicalDocumentDto parse(String rawJson) {
        try {
            return JSON.readValue(rawJson, CanonicalDocumentDto.class);
        } catch (Exception e) {
            // fixed client message (R10, the M2 hygiene rule): Jackson parse
            // errors echo request-derived excerpts into the 400 body — the
            // detail belongs in the log, not the response
            LOG.warn("canonical document parse rejected: {}", e.getMessage());
            throw new InvalidDocumentException(
                    "request body is not a canonical document (schema 1.0)");
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
