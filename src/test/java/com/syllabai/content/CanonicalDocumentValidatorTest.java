package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-013: core-side canonical validation mirrors the parser's §8 invariants —
 * never trust input across a process boundary. All violations of one document
 * are reported in a single rejection.
 */
class CanonicalDocumentValidatorTest {

    private final CanonicalDocumentValidator validator = new CanonicalDocumentValidator();

    @Test
    @DisplayName("a valid parser fixture passes")
    void validPasses() {
        assertThatCode(() -> validator.validate(CanonicalDocs.valid()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("wrong schemaVersion is rejected")
    void rejectsSchema() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "2.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                doc.textBlocks(), doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("missing source checksum is rejected")
    void rejectsMissingChecksum() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), new CanonicalDocumentDto.SourceInfo("qp.pdf", null, null,
                        "application/pdf", null), doc.pageCount(), doc.pages(), doc.sections(),
                doc.textBlocks(), doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("source.checksum");
    }

    @Test
    @DisplayName("element page outside pageCount is rejected")
    void rejectsPageOutsideRange() {
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                CanonicalDocs.block("e999999", 3, 0, "off-page"));
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                blocks, doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("outside 1..2");
    }

    @Test
    @DisplayName("duplicate element ids are rejected")
    void rejectsDuplicateIds() {
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                CanonicalDocs.block("e000000", 1, 0, "first"),
                CanonicalDocs.block("e000000", 1, 1, "second"));
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                blocks, doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("duplicate element_id e000000");
    }

    @Test
    @DisplayName("textless textBlocks are tolerated (layout-only elements; chunking skips them)")
    void toleratesTextlessTextBlock() {
        List<CanonicalDocumentDto.TextBlockElement> blocks = List.of(
                CanonicalDocs.block("e000000", 1, 0, "real text"),
                CanonicalDocs.block("e000001", 1, 1, " "));
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto withBlank = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                blocks, doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        // the real 4CH0 QP fixture ships a null-text textBlock (e000034) — same case
        assertThatCode(() -> validator.validate(withBlank)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("section referencing an unknown element is rejected")
    void rejectsDanglingSectionRef() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        List<CanonicalDocumentDto.SectionInfo> sections = List.of(
                new CanonicalDocumentDto.SectionInfo("s002", "Bad", 2, 1,
                        List.of("e-nope")));
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), sections,
                doc.textBlocks(), doc.tables(), doc.figures(), doc.equations(), doc.provenance());
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("references unknown element e-nope");
    }

    @Test
    @DisplayName("missing provenance engine is rejected alongside other violations")
    void collectsMultipleViolations() {
        CanonicalDocumentDto doc = CanonicalDocs.valid();
        CanonicalDocumentDto bad = new CanonicalDocumentDto(doc.documentId(), "1.0",
                doc.version(), doc.source(), doc.pageCount(), doc.pages(), doc.sections(),
                doc.textBlocks(), doc.tables(), doc.figures(), doc.equations(),
                new CanonicalDocumentDto.ProvenanceInfo(null, null, null, null,
                        "syllabai-parser", "1.0"));
        assertThatThrownBy(() -> validator.validate(bad))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("provenance.engine is required")
                .hasMessageContaining("provenance.engineVersion is required");
        assertThat(1).isEqualTo(1);
    }
}
