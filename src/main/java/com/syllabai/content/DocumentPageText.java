package com.syllabai.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Page-scoped text extraction from a parsed canonical document — the text side
 * of the learner citation view (L5): a citation pointing at page N renders THE
 * TEXT OF THAT PAGE, assembled from the sealed canonical elements in reading
 * order. Text-bearing element families only (text blocks + tables + equations,
 * the exact set the retrieval index embeds); figures carry no text layer and
 * contribute nothing. Pure function over the DTO — no repository, no I/O, no
 * cache: a citation drill-in is user-initiated and rare.
 *
 * <p>Verbatim law: element text is joined, never rewrapped, re-trimmed or
 * re-ordered beyond the canonical {@code reading_order} — the page a learner
 * sees is the page the document was ingested with (§8 sealed canonical).</p>
 */
final class DocumentPageText {

    /** One text-bearing element placed on the page: its canonical reading
     *  order (nullable — sorts last, never first) and its verbatim text. */
    private record Piece(Integer readingOrder, String text) {
    }

    private DocumentPageText() {
    }

    /**
     * Verbatim text of {@code page} (1-based, matching {@code page_number}),
     * elements in canonical reading order joined by newlines. A text-free page
     * (figures only, or a page range gap) yields the empty string — an honest
     * "this page carries no text layer", never a fabricated placeholder.
     */
    static String of(CanonicalDocumentDto doc, int page) {
        List<Piece> pieces = new ArrayList<>();
        collectTextBlocks(pieces, page, doc.textBlocks());
        collectTables(pieces, page, doc.tables());
        collectEquations(pieces, page, doc.equations());
        // stable sort: equal reading_order keeps canonical document order;
        // a missing reading_order sorts last within the page, never first
        pieces.sort(Comparator.comparing(Piece::readingOrder,
                Comparator.nullsLast(Comparator.naturalOrder())));
        StringBuilder joined = new StringBuilder();
        for (Piece piece : pieces) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(piece.text());
        }
        return joined.toString();
    }

    private static void collectTextBlocks(List<Piece> pieces, int page,
                                          List<CanonicalDocumentDto.TextBlockElement> elements) {
        if (elements == null) {
            return;
        }
        for (CanonicalDocumentDto.TextBlockElement element : elements) {
            if (element != null && element.text() != null
                    && Integer.valueOf(page).equals(element.pageNumber())) {
                pieces.add(new Piece(element.readingOrder(), element.text()));
            }
        }
    }

    private static void collectTables(List<Piece> pieces, int page,
                                      List<CanonicalDocumentDto.TableElement> elements) {
        if (elements == null) {
            return;
        }
        for (CanonicalDocumentDto.TableElement element : elements) {
            if (element != null && element.text() != null
                    && Integer.valueOf(page).equals(element.pageNumber())) {
                pieces.add(new Piece(element.readingOrder(), element.text()));
            }
        }
    }

    private static void collectEquations(List<Piece> pieces, int page,
                                         List<CanonicalDocumentDto.EquationElement> elements) {
        if (elements == null) {
            return;
        }
        for (CanonicalDocumentDto.EquationElement element : elements) {
            if (element != null && element.text() != null
                    && Integer.valueOf(page).equals(element.pageNumber())) {
                pieces.add(new Piece(element.readingOrder(), element.text()));
            }
        }
    }
}
