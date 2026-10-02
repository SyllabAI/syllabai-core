package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.content.CanonicalDocumentDto.EquationElement;
import com.syllabai.content.CanonicalDocumentDto.TableElement;
import com.syllabai.content.CanonicalDocumentDto.TextBlockElement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Page-scoped text extraction for the learner citation view (L5): reading
 * order is the contract, page filtering is exact, and a text-free page is the
 * empty string — never null, never a placeholder.
 */
class DocumentPageTextTest {

    private static TextBlockElement text(String elementId, int page, Integer readingOrder,
                                         String content) {
        return new TextBlockElement(elementId, "text", page, null, content, readingOrder,
                null, null, null, "e", "1", null);
    }

    private static TableElement table(String elementId, int page, Integer readingOrder,
                                      String content) {
        return new TableElement(elementId, "table", page, null, content, readingOrder,
                null, List.of(), 1, 1, "e", "1", null);
    }

    private static EquationElement equation(String elementId, int page, Integer readingOrder,
                                            String content) {
        return new EquationElement(elementId, "equation", page, null, content, readingOrder,
                null, null, "e", "1", null);
    }

    private static CanonicalDocumentDto doc(List<TextBlockElement> textBlocks,
                                            List<TableElement> tables,
                                            List<EquationElement> equations) {
        return new CanonicalDocumentDto("doc-1", "1.0", 1, null, 3, List.of(), List.of(),
                textBlocks, tables, List.of(), equations, null, null);
    }

    @Test
    @DisplayName("assembles the requested page in reading order across families")
    void readingOrderAcrossFamilies() {
        CanonicalDocumentDto d = doc(
                List.of(text("t2", 2, 2, "second"),
                        text("t1", 2, 1, "first"),
                        text("t-other-page", 1, 0, "not this page"),
                        text("t3", 2, 3, "third")),
                List.of(table("tb1", 2, 4, "table row")),
                List.of(equation("eq1", 2, 5, "E=mc2")));

        String page2 = DocumentPageText.of(d, 2);

        assertThat(page2)
                .isEqualTo("first\nsecond\nthird\ntable row\nE=mc2");
        assertThat(DocumentPageText.of(d, 1)).isEqualTo("not this page");
    }

    @Test
    @DisplayName("equal reading_order keeps canonical document order (stable sort)")
    void tiesKeepCanonicalOrder() {
        CanonicalDocumentDto d = doc(
                List.of(text("a", 1, 7, "alpha"), text("b", 1, 7, "beta")), List.of(), List.of());

        assertThat(DocumentPageText.of(d, 1)).isEqualTo("alpha\nbeta");
    }

    @Test
    @DisplayName("a missing reading_order sorts last within the page, never first")
    void nullReadingOrderSortsLast() {
        CanonicalDocumentDto d = doc(
                List.of(text("z", 1, null, "no-order"), text("a", 1, 1, "ordered")),
                List.of(), List.of());

        assertThat(DocumentPageText.of(d, 1)).isEqualTo("ordered\nno-order");
    }

    @Test
    @DisplayName("a text-free page is the empty string; null families tolerated")
    void textFreePageAndNullFamilies() {
        CanonicalDocumentDto d = doc(List.of(text("t1", 1, 1, "only page one")), null, List.of());

        assertThat(DocumentPageText.of(d, 2)).isEmpty();
        assertThat(DocumentPageText.of(d, 3)).isEmpty();
        assertThat(DocumentPageText.of(doc(null, null, null), 1)).isEmpty();
    }

    @Test
    @DisplayName("null text and null elements are skipped, never rendered as 'null'")
    void nullTextSkipped() {
        CanonicalDocumentDto d = doc(
                List.of(text("a", 1, 1, "kept"),
                        new TextBlockElement("b", "text", 1, null, null, 2, null, null, null,
                                "e", "1", null),
                        null),
                List.of(), List.of());

        assertThat(DocumentPageText.of(d, 1)).isEqualTo("kept");
    }
}
