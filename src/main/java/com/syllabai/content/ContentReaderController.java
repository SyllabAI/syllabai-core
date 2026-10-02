package com.syllabai.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.shared.NotFoundException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner-facing citation read surface (L5): tutor citations deep-link
 * here instead of the teacher content API, so the {@code [n]} chips resolve
 * for STUDENTS — the audience the citations are written for (T-024 renders
 * "Mark scheme — p6" as a link; until this endpoint the link died on a
 * TEACHER-only route for every learner). One route, two shapes: without
 * {@code page} the document header; with {@code page} the verbatim page text
 * assembled from the sealed canonical elements in reading order
 * ({@link DocumentPageText}).
 *
 * <p>Route security: authenticated surface (same basis as the question-asset
 * endpoints) — the method-level check mirrors the deep-audit 09-28 M5
 * defense-in-depth pattern so a future route-matcher change cannot silently
 * open the surface. Corpus law parity (T-C20) is the real gate: only a
 * document on the SERVING side of the validation gates is readable here — the
 * question paper / mark scheme of a VALIDATED exam paper, or a VALIDATED
 * knowledge-layer document ({@link DocumentRepository#existsCitable}). A
 * corpus import nothing serves is an honest 404, byte-identical to the
 * unknown-id response — no state leak, no unvalidated content on any learner
 * surface. Teachers keep the full canonical/debug surface on the teacher API;
 * this endpoint is the citation target, not an ops tool.</p>
 *
 * <p>F-022 tranche 2: the header also carries the exam-paper IDENTITY behind a
 * QUESTION_PAPER / MARK_SCHEME row ({@link PaperRef} — paperCode, sessionLabel,
 * role), resolved through the T-011/T-013 join by business document id. That is
 * the key the hub needs to map the citation onto the REAL paper PDF (its own
 * corpus viewer); it is pure metadata and rides the same existsCitable gate —
 * the content gate is unchanged.</p>
 */
@RestController
@RequestMapping("/api/v1/content/documents")
@PreAuthorize("isAuthenticated()")
public class ContentReaderController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentRepository documents;
    private final ExamPaperRepository examPapers;

    public ContentReaderController(DocumentRepository documents,
                                   ExamPaperRepository examPapers) {
        this.documents = documents;
        this.examPapers = examPapers;
    }

    @GetMapping("/{id}")
    public CitationDocumentView get(@PathVariable UUID id,
                                    @RequestParam(required = false) Integer page) {
        Document doc = documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Document", id));
        if (!documents.existsCitable(id)) {
            // corpus law: nothing serves without human validation — an honest
            // 404 identical to the unknown-id response (no state leak)
            throw new NotFoundException("Document", id);
        }
        if (page == null) {
            return CitationDocumentView.from(doc, null, null, paperRefOf(doc));
        }
        if (page < 1 || page > doc.pageCount()) {
            throw new NotFoundException("Document page " + page, id);
        }
        // the parse cost is paid only by requests that already passed the gate
        return CitationDocumentView.from(doc, page, pageText(doc, page), paperRefOf(doc));
    }

    /**
     * The paper identity behind a QP/MS row — null for every other kind and
     * for QP/MS rows no exam paper links (a hand-registered document). Bounded
     * by construction: the finder matches the business document id, which the
     * T-013 bridge writes exactly once per imported pair.
     */
    private PaperRef paperRefOf(Document doc) {
        if (doc.kind() != Document.Kind.QUESTION_PAPER
                && doc.kind() != Document.Kind.MARK_SCHEME) {
            return null;
        }
        return examPapers.findAllByLinkedDocumentId(doc.documentId()).stream()
                .findFirst()
                .map(p -> new PaperRef(p.id(), p.paperCode(), p.sessionLabel(),
                        doc.kind() == Document.Kind.QUESTION_PAPER ? "QP" : "MS"))
                .orElse(null);
    }

    /** Parse errors of the sealed JSONB are a corrupted-row condition — fail
     *  loud server-side (500), never echo row content into the response (R10:
     *  fixed client message). */
    private static String pageText(Document doc, int page) {
        try {
            return DocumentPageText.of(
                    JSON.readValue(doc.canonicalJson(), CanonicalDocumentDto.class), page);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "stored canonical JSON failed to parse for document " + doc.id(), e);
        }
    }

    /**
     * The citation view: document identity + (optionally) the requested page's
     * verbatim text + the exam-paper identity when the row is a paper document.
     * {@code page}/{@code text} are {@code null} for the header shape and
     * {@code paper} is {@code null} for non-paper rows — one record keeps the
     * deep-link target a single fetch.
     */
    public record CitationDocumentView(UUID id, String documentId, int docVersion, String kind,
                                       String title, int pageCount, Integer page, String text,
                                       PaperRef paper) {

        static CitationDocumentView from(Document d, Integer page, String text, PaperRef paper) {
            return new CitationDocumentView(d.id(), d.documentId(), d.docVersion(),
                    d.kind().name(), d.fileName() == null ? d.sourceUri() : d.fileName(),
                    d.pageCount(), page, text, paper);
        }
    }

    /**
     * F-022 tranche 2 — the T-011 exam-paper identity a paper citation belongs
     * to. {@code role} is "QP" or "MS" (which side of the pair this document
     * is); {@code paperCode}/{@code sessionLabel} are the import metadata the
     * hub's corpus index is addressed by. Metadata only — never a serving
     * authority (the content gate stays {@link DocumentRepository#existsCitable}).
     */
    public record PaperRef(UUID paperId, String paperCode, String sessionLabel, String role) {
    }
}
