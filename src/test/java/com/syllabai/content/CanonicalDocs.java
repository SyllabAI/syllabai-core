package com.syllabai.content;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small canonical-document builder for T-013 unit tests — valid by default, with
 * knobs to break single invariants.
 */
final class CanonicalDocs {

    private CanonicalDocs() {
    }

    static CanonicalDocumentDto valid() {
        // P-6: documentId is derived (checksum+engine+engineVersion), not
        // free-form — the fixture mints it through the validator's identity
        // mirror so the derivation check holds.
        String checksum = sha256();
        String documentId = CanonicalDocumentValidator.derivedDocumentId(
                checksum, "opendataloader-pdf", "2.5.7");
        return new CanonicalDocumentDto(
                documentId,
                "1.0",
                1,
                new CanonicalDocumentDto.SourceInfo("qp.pdf", checksum, "SHA-256",
                        "application/pdf", "qp.pdf"),
                2,
                List.of(new CanonicalDocumentDto.PageInfo(1, 595.0, 842.0),
                        new CanonicalDocumentDto.PageInfo(2, 595.0, 842.0)),
                List.of(new CanonicalDocumentDto.SectionInfo("s001", "Section", 2, 1,
                        List.of("e000000", "e000001"))),
                List.of(
                        block("e000000", 1, 0, "Explain why the rate increases with temperature."),
                        block("e000001", 1, 1, "Particles gain kinetic energy and collide more often."),
                        block("e000002", 2, 0, "State the unit of rate of reaction.")),
                List.of(table("e100000", 2, 1, "Time (s) | Volume (cm3)")),
                List.of(),
                List.of(),
                new CanonicalDocumentDto.ProvenanceInfo("opendataloader-pdf", "2.5.7",
                        "2026-09-03T08:01:48.225885671Z",
                        java.util.Map.of("mode", "fast"), "syllabai-parser", "1.0"));
    }

    static CanonicalDocumentDto.TextBlockElement block(String id, int page, int order,
                                                        String text) {
        return new CanonicalDocumentDto.TextBlockElement(id, "text_block", page,
                new CanonicalDocumentDto.BoundingBox(1.0, 1.0, 10.0, 10.0, "pt"),
                text, order, 1.0, "paragraph", null, "opendataloader-pdf", "2.5.7");
    }

    static CanonicalDocumentDto.TableElement table(String id, int page, int order, String text) {
        return new CanonicalDocumentDto.TableElement(id, "table", page,
                new CanonicalDocumentDto.BoundingBox(1.0, 1.0, 10.0, 10.0, "pt"),
                text, order, 1.0, List.of(List.of("Time", "Volume")), 1, 2,
                "opendataloader-pdf", "2.5.7");
    }

    /** N pages of M same-sized blocks — page p holds blocks ordered by reading order. */
    static CanonicalDocumentDto bulk(int pages, int blocksPerPage) {
        List<CanonicalDocumentDto.TextBlockElement> blocks = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            for (int b = 0; b < blocksPerPage; b++) {
                blocks.add(block("e" + p + "-" + b, p, b,
                        "block " + p + "-" + b + " " + "lorem ".repeat(8)));
            }
        }
        return new CanonicalDocumentDto(UUID.randomUUID().toString(), "1.0", 1,
                new CanonicalDocumentDto.SourceInfo("bulk.pdf", sha256(), "SHA-256",
                        "application/pdf", "bulk.pdf"),
                pages, List.of(), List.of(), blocks, List.of(), List.of(), List.of(),
                valid().provenance());
    }

    private static String sha256() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }
}
