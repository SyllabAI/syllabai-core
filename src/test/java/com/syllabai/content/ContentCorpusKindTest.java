package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C06 notes ingestion (V29): the instructional corpus kinds are ingestable
 * through the existing canonical pipeline and corpus imports are born
 * SUGGESTED on the document itself — nothing serves without human validation.
 */
class ContentCorpusKindTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final com.syllabai.curriculum.SubjectRepository subjects =
            mock(com.syllabai.curriculum.SubjectRepository.class);
    private final ContentIngestionService service = new ContentIngestionService(
            new CanonicalDocumentValidator(), new ChunkingService(300, 800),
            documents, chunks, subjects);

    private final List<Document> savedDocuments = new ArrayList<>();

    {
        when(documents.findByChecksum(any())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            savedDocuments.add(d);
            return d;
        });
        when(chunks.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("V29 widens the document kind enum with the three corpus roles")
    void kindEnumCarriesCorpusRoles() {
        assertThat(Document.Kind.valueOf("EXTERNAL_NOTES")).isNotNull();
        assertThat(Document.Kind.valueOf("EXTERNAL_QUESTIONS")).isNotNull();
        assertThat(Document.Kind.valueOf("TEXTBOOK")).isNotNull();
        assertThat(Document.Kind.values()).hasSize(7);
    }

    @Test
    @DisplayName("an EXTERNAL_NOTES canonical document persists and is born SUGGESTED")
    void externalNotesIngestLandsSuggested() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        String raw = "{\"documentId\":\"" + doc.documentId() + "\"}";

        ContentIngestionService.IngestionResult result =
                service.ingest(doc, raw, Document.Kind.EXTERNAL_NOTES, null);

        assertThat(result.duplicate()).isFalse();
        assertThat(savedDocuments).hasSize(1);
        Document saved = savedDocuments.get(0);
        assertThat(saved.kind()).isEqualTo(Document.Kind.EXTERNAL_NOTES);
        assertThat(saved.validationState()).isEqualTo("SUGGESTED");
        assertThat(result.chunks()).isGreaterThan(0);
    }

    @Test
    @DisplayName("a Document entity defaults to SUGGESTED even via the legacy constructor")
    void legacyConstructorDefaultsSuggested() {
        Document legacy = new Document("doc-1", "1.0", 1, Document.Kind.EXTERNAL_NOTES,
                "https://example.test/note", "note.md", "text/markdown",
                "deadbeef", "SHA-256", 1, 3, 3, 1,
                "sme-revision-note", "1.0.0", null, "{}", null);
        assertThat(legacy.validationState()).isEqualTo("SUGGESTED");
    }
}
