package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-013: canonical documents land verbatim with deterministic chunks; re-POSTing
 * the same source is an idempotent no-op; a checksum already registered under a
 * different kind is a loud conflict, not a silent overwrite.
 */
class ContentIngestionServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final ContentIngestionService service = new ContentIngestionService(
            new CanonicalDocumentValidator(), new ChunkingService(300, 800),
            documents, chunks);

    private final List<Document> savedDocuments = new ArrayList<>();
    private final List<DocumentChunk> savedChunks = new ArrayList<>();

    {
        when(documents.findByChecksum(any())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            savedDocuments.add(d);
            return d;
        });
        when(chunks.save(any())).thenAnswer(inv -> {
            DocumentChunk c = inv.getArgument(0);
            savedChunks.add(c);
            return c;
        });
    }

    @Test
    @DisplayName("a valid canonical document persists with chunks and provenance")
    void ingestHappyPath() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        String raw = "{\"documentId\":\"" + doc.documentId() + "\"}";
        UUID teacher = UUID.randomUUID();

        ContentIngestionService.IngestionResult result =
                service.ingest(doc, raw, Document.Kind.QUESTION_PAPER, teacher);

        assertThat(result.duplicate()).isFalse();
        assertThat(savedDocuments).hasSize(1);
        Document saved = savedDocuments.get(0);
        assertThat(saved.documentId()).isEqualTo(doc.documentId());
        assertThat(saved.kind()).isEqualTo(Document.Kind.QUESTION_PAPER);
        assertThat(saved.checksum()).isEqualTo(doc.source().checksum());
        assertThat(saved.canonicalJson()).isEqualTo(raw); // verbatim, not re-serialized
        assertThat(saved.sourceEngine()).isEqualTo("opendataloader-pdf");
        assertThat(saved.elementCount()).isEqualTo(doc.totalElementCount());

        assertThat(result.chunks()).isEqualTo(savedChunks.size());
        assertThat(result.chunks()).isGreaterThan(0);
        assertThat(savedChunks.get(0).documentRowId()).isEqualTo(saved.id());
        for (int i = 0; i < savedChunks.size(); i++) {
            assertThat(savedChunks.get(i).chunkIndex()).isEqualTo(i);
            assertThat(savedChunks.get(i).embeddingModel()).isNull(); // un-embedded first
        }
    }

    @Test
    @DisplayName("re-ingesting the same checksum is an idempotent no-op")
    void dedupByChecksum() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        Document existing = new Document(doc.documentId(), "1.0", 1,
                Document.Kind.QUESTION_PAPER, doc.source().uri(), doc.source().fileName(),
                doc.source().mimeType(), doc.source().checksum(), "SHA-256", 2, 4, 4, 1,
                "opendataloader-pdf", "2.5.7", null, "{}", null);
        when(documents.findByChecksum(doc.source().checksum()))
                .thenReturn(Optional.of(existing));

        ContentIngestionService.IngestionResult result =
                service.ingest(doc, "{}", Document.Kind.QUESTION_PAPER, null);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.id()).isEqualTo(existing.id());
        verify(documents, never()).save(any());
        verify(chunks, never()).save(any());
        assertThat(savedDocuments).isEmpty();
    }

    @Test
    @DisplayName("same checksum under a different kind is a loud conflict")
    void kindConflict() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        Document existing = new Document(doc.documentId(), "1.0", 1,
                Document.Kind.MARK_SCHEME, doc.source().uri(), doc.source().fileName(),
                doc.source().mimeType(), doc.source().checksum(), "SHA-256", 2, 4, 4, 1,
                "opendataloader-pdf", "2.5.7", null, "{}", null);
        when(documents.findByChecksum(doc.source().checksum()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.ingest(doc, "{}", Document.Kind.QUESTION_PAPER, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("MARK_SCHEME");
    }

    @Test
    @DisplayName("an invalid document is rejected before anything persists")
    void invalidDocumentRejected() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "9.9",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                doc.textBlocks(), doc.tables(), doc.figures(), doc.equations(), doc.provenance());

        assertThatThrownBy(() -> service.ingest(bad, "{}", Document.Kind.OTHER, null))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("schemaVersion");

        verify(documents, never()).save(any());
        assertThat(savedChunks).isEmpty();
    }
}
