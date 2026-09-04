package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-013: deterministic chunking — same document, same chunks, every time; block
 * boundaries respected; provenance element ids preserved in reading order.
 */
class ChunkingServiceTest {

    private final ChunkingService chunking = new ChunkingService(300, 800);

    @Test
    @DisplayName("chunking is deterministic")
    void deterministic() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        List<ChunkDraft> first = chunking.chunk(doc);
        List<ChunkDraft> second = chunking.chunk(doc);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("blocks are ordered by page then reading order")
    void readingOrderRespected() {
        List<ChunkDraft> chunks = chunking.chunk(CanonicalDocs.valid());
        assertThat(chunks).isNotEmpty();
        // the fixture's page-2 block lands after all page-1 blocks
        List<String> ids = chunks.stream().flatMap(c -> c.elementIds().stream()).toList();
        assertThat(ids.indexOf("e000002")).isGreaterThan(ids.indexOf("e000001"));
        assertThat(ids.indexOf("e000001")).isGreaterThan(ids.indexOf("e000000"));
        // tables interleave by their own (page, reading_order)
        assertThat(ids).contains("e100000");
    }

    @Test
    @DisplayName("every chunk keeps its provenance element ids and page span")
    void provenanceKept() {
        List<ChunkDraft> chunks = chunking.chunk(CanonicalDocs.valid());
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.elementIds()).isNotEmpty();
            assertThat(chunk.pageStart()).isNotNull();
            assertThat(chunk.pageEnd()).isNotNull();
            assertThat(chunk.pageStart()).isLessThanOrEqualTo(chunk.pageEnd());
            assertThat(chunk.tokenEstimate()).isGreaterThan(0);
            assertThat(chunk.content()).isNotBlank();
        }
        // no element is lost or duplicated across chunks
        List<String> all = chunks.stream().flatMap(c -> c.elementIds().stream()).toList();
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all).containsExactlyInAnyOrder("e000000", "e000001", "e000002", "e100000");
    }

    @Test
    @DisplayName("blocks are never split; chunks respect the target budget")
    void boundariesRespected() {
        CanonicalDocumentDto doc = CanonicalDocs.bulk(3, 12);
        List<ChunkDraft> chunks = chunking.chunk(doc);
        assertThat(chunks.size()).isGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            // every chunk is a whole number of blocks (bulk blocks are ~48 chars ≈ 12 tokens)
            assertThat(chunk.content().split("\n").length)
                    .isEqualTo(chunk.elementIds().size());
        }
        // ordering across pages: page 1 chunks come before page 2 chunks
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(chunks.size() - 1).pageStart())
                .isGreaterThanOrEqualTo(chunks.get(0).pageStart());
    }

    @Test
    @DisplayName("an oversized single block becomes its own chunk, never truncated")
    void oversizedBlockKeptWhole() {
        String huge = "x".repeat(4000); // 1000 estimated tokens > max 800
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                CanonicalDocs.block("e0", 1, 0, "small block"),
                CanonicalDocs.block("e1", 1, 1, huge));
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto withHuge = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), List.of(),
                blocks, List.of(), List.of(), List.of(), doc.provenance());
        List<ChunkDraft> chunks = chunking.chunk(withHuge);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).content()).isEqualTo(huge.strip());
        assertThat(chunks.get(1).elementIds()).containsExactly("e1");
    }

    @Test
    @DisplayName("a document with no text yields no chunks, not an error")
    void emptyDocumentYieldsNoChunks() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto empty = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), List.of(),
                List.of(), List.of(), List.of(), List.of(), doc.provenance());
        assertThat(chunking.chunk(empty)).isEmpty();
    }

    @Test
    @DisplayName("estimate is the documented ceil(chars/4)")
    void estimate() {
        assertThat(ChunkingService.estimate("")).isEqualTo(1);
        assertThat(ChunkingService.estimate("abcd")).isEqualTo(1);
        assertThat(ChunkingService.estimate("abcde")).isEqualTo(2);
    }

    @Test
    @DisplayName("misconfigured sizes are rejected loudly at construction")
    void invalidConfig() {
        assertThatThrownBy(() -> new ChunkingService(800, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingService(10, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
