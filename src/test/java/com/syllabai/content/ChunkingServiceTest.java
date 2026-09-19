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
                blocks, List.of(), List.of(), List.of(), doc.provenance(), null);
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
                List.of(), List.of(), List.of(), List.of(), doc.provenance(), null);
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

    // ── Embedding v2 (plan §4.1): atom-boundary mode + per-chunk headers ──────

    @Test
    @DisplayName("V33: a group-key change is a hard boundary — no chunk crosses an atom")
    void atomBoundaryRespected() {
        CanonicalDocumentDto doc = CanonicalDocs.twoAtoms(4);
        List<ChunkDraft> chunks = chunking.chunk(doc, Document.Kind.QUESTION_PAPER);

        assertThat(chunks).isNotEmpty();
        for (ChunkDraft chunk : chunks) {
            // every chunk carries exactly one group key (never a mix)
            List<String> ids = chunk.elementIds();
            boolean fromAtomOne = ids.get(0).startsWith("g1-");
            for (String id : ids) {
                assertThat(id.startsWith(fromAtomOne ? "g1-" : "g2-")).isTrue();
            }
            assertThat(chunk.groupKey()).isEqualTo(fromAtomOne ? "q3" : "q4");
        }
        // both atoms produced chunks
        assertThat(chunks.stream().map(ChunkDraft::groupKey).distinct())
                .containsExactlyInAnyOrder("q3", "q4");
    }

    @Test
    @DisplayName("V33: oversized block inside an atom splits at block boundary WITHIN the atom")
    void oversizedInsideAtomStaysInsideAtom() {
        String huge = "x".repeat(4000); // 1000 estimated tokens > max 800
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                CanonicalDocs.groupedBlock("a-0", 1, 0, "atom q3 small block", "q3"),
                CanonicalDocs.groupedBlock("a-1", 1, 1, huge, "q3"),
                CanonicalDocs.groupedBlock("a-2", 1, 2, "atom q3 after block", "q3"),
                CanonicalDocs.groupedBlock("b-0", 2, 0, "atom q4 block", "q4"));
        CanonicalDocumentDto grouped = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), List.of(),
                blocks, List.of(), List.of(), List.of(), doc.provenance(), null);

        List<ChunkDraft> chunks = chunking.chunk(grouped, Document.Kind.QUESTION_PAPER);

        // no chunk mixes group keys, and q3 still yields chunks after the oversized one
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).groupKey()).isEqualTo("q3");
        assertThat(chunks.get(0).elementIds()).containsExactly("a-0");
        assertThat(chunks.get(1).groupKey()).isEqualTo("q3"); // oversized, own chunk
        assertThat(chunks.get(1).elementIds()).containsExactly("a-1");
        assertThat(chunks.get(2).groupKey()).isEqualTo("q3"); // same atom continues
        assertThat(chunks.get(2).elementIds()).containsExactly("a-2");
        assertThat(chunks.get(3).groupKey()).isEqualTo("q4");
        assertThat(chunks.get(3).elementIds()).containsExactly("b-0");
    }

    @Test
    @DisplayName("V33: every chunk of a retrieval-carrying document is stamped with the header")
    void headerProjectedOnEveryChunk() {
        CanonicalDocumentDto doc = CanonicalDocs.twoAtoms(2);
        List<ChunkDraft> chunks = chunking.chunk(doc, Document.Kind.QUESTION_PAPER);

        assertThat(chunks).hasSize(2);
        for (ChunkDraft chunk : chunks) {
            String[] lines = chunk.content().split("\n", 2);
            assertThat(lines).hasSize(2);
            // subject segment qualifies title + code; series/year/paper/qref/pages present
            assertThat(lines[0])
                    .isEqualTo("IGCSE Chemistry 4CH1 | Jun 2022 | 1C | Q"
                            + chunk.groupKey().substring(1) + " | pp."
                            + chunk.pageStart() + "–" + chunk.pageEnd());
            // header is prepended, body follows untouched
            assertThat(lines[1]).startsWith("atom ");
        }
    }

    @Test
    @DisplayName("V33: legacy-shaped documents (no retrieval block) get no header — byte-identical bodies")
    void legacyDocumentsUnchanged() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        List<ChunkDraft> chunks = chunking.chunk(doc, Document.Kind.QUESTION_PAPER);
        assertThat(chunks).isNotEmpty();
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.groupKey()).isNull();
            // no identity material in the document ⇒ no header line anywhere
            assertThat(chunk.content()).doesNotContain("IGCSE");
            assertThat(chunk.content().lines().count())
                    .isEqualTo(chunk.elementIds().size());
        }
    }

    @Test
    @DisplayName("V33: a mark scheme chunk without explicit label carries the MS prefix in the q segment")
    void markSchemeHeaderPrefix() {
        CanonicalDocumentDto doc = CanonicalDocs.twoAtoms(1);
        List<ChunkDraft> chunks = chunking.chunk(doc, Document.Kind.MARK_SCHEME);
        assertThat(chunks).isNotEmpty();
        String[] lines = chunks.get(0).content().split("\n", 2);
        assertThat(lines[0]).contains(" | MS Q3 | ");
    }

    @Test
    @DisplayName("V33: notes header composes label + numeric-aware spec range")
    void notesHeaderComposition() {
        assertThat(ChunkHeaderBuilder.specRangeSegment(List.of("1.25", "1.22", "1.10")))
                .isEqualTo("Spec 1.10–1.25");
        assertThat(ChunkHeaderBuilder.specRangeSegment(List.of("1.22")))
                .isEqualTo("Spec 1.22");
        assertThat(ChunkHeaderBuilder.specRangeSegment(List.of())).isEmpty();
        assertThat(ChunkHeaderBuilder.displaySeries("JUN")).isEqualTo("Jun");
        assertThat(ChunkHeaderBuilder.normalizeAtom("q14")).isEqualTo("14");
        assertThat(ChunkHeaderBuilder.normalizeAtom("Q14b")).isEqualTo("14b");
        assertThat(ChunkHeaderBuilder.normalizeAtom(null)).isNull();
    }
}
