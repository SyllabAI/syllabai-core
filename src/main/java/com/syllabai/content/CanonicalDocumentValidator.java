package com.syllabai.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Core-side mirror of the parser's canonical invariants (Master Spec §8). The parser
 * guarantees these before writing the JSON; core re-validates because input crossing
 * a process boundary is never trusted. Every violation is collected — one rejection
 * lists them all — so a corpus operator fixes a bad document in a single pass.
 */
@Component
public class CanonicalDocumentValidator {

    public void validate(CanonicalDocumentDto doc) {
        List<String> violations = new ArrayList<>();

        if (doc == null) {
            throw new InvalidDocumentException("canonical document is null");
        }
        if (doc.schemaVersion() == null
                || !CanonicalDocumentDto.SUPPORTED_SCHEMA.equals(doc.schemaVersion())) {
            violations.add("schemaVersion must be \"1.0\" (was " + quote(doc.schemaVersion()) + ")");
        }
        if (isBlank(doc.documentId())) {
            violations.add("documentId is required");
        }
        if (doc.version() == null || doc.version() < 1) {
            violations.add("version must be >= 1 (was " + doc.version() + ")");
        }

        // §8 source: uri + checksum + mimeType are the provenance spine
        if (doc.source() == null) {
            violations.add("source is required");
        } else {
            if (isBlank(doc.source().uri())) {
                violations.add("source.uri is required");
            }
            if (isBlank(doc.source().checksum())) {
                violations.add("source.checksum is required");
            }
            if (isBlank(doc.source().mimeType())) {
                violations.add("source.mimeType is required");
            }
        }

        if (doc.pageCount() == null || doc.pageCount() < 1) {
            violations.add("pageCount must be >= 1 (was " + doc.pageCount() + ")");
        }

        if (doc.provenance() == null) {
            violations.add("provenance is required");
        } else {
            if (isBlank(doc.provenance().engine())) {
                violations.add("provenance.engine is required");
            }
            if (isBlank(doc.provenance().engineVersion())) {
                violations.add("provenance.engineVersion is required");
            }
            // P-6 mirror: documentId is DERIVED, not free-form — re-derive it from
            // the identity material and fail on drift. A mismatched id would
            // silently break consumer dedup across process boundaries.
            if (!isBlank(doc.documentId()) && doc.source() != null
                    && !isBlank(doc.source().checksum())
                    && !isBlank(doc.provenance().engine())
                    && !isBlank(doc.provenance().engineVersion())) {
                String derived = derivedDocumentId(doc.source().checksum(),
                        doc.provenance().engine(), doc.provenance().engineVersion());
                if (!derived.equals(doc.documentId())) {
                    violations.add("documentId does not match its checksum+engine+"
                            + "engineVersion derivation (expected " + derived + ")");
                }
            }
        }

        // retrieval identity (Embedding v2, plan §4.2/§8.1): optional as a whole,
        // but any series that IS present must be the canonical enum — a raw
        // label like "Summer 2019" stored as a filter is a lie waiting for a
        // year-range query (plan §12 anti-pattern 7)
        if (doc.retrieval() != null) {
            CanonicalDocumentDto.RetrievalMeta meta = doc.retrieval();
            if (meta.series() != null && !meta.series().isBlank()
                    && !Set.of("JAN", "JUN", "NOV").contains(meta.series().strip())) {
                violations.add("retrieval.series must be JAN, JUN or NOV (was "
                        + quote(meta.series()) + ") — canonicalize \"Summer\"→JUN, "
                        + "\"October/November\"→NOV before ingest");
            }
            if (meta.year() != null && (meta.year() < 1950 || meta.year() > 2100)) {
                violations.add("retrieval.year must be a plausible exam year (was "
                        + meta.year() + ")");
            }
        }

        // text may legitimately be null (layout-only elements — the real 4CH0 QP
        // fixture ships one); chunking skips them, so validation tolerates them
        Set<String> elementIds = new HashSet<>();
        List<String> elementViolations = new ArrayList<>();
        if (doc.textBlocks() != null) {
            for (CanonicalDocumentDto.TextBlockElement e : doc.textBlocks()) {
                checkElement(doc, e == null ? null : e.elementId(), e == null ? null : e.elementType(),
                        e == null ? null : e.pageNumber(), e == null ? null : e.readingOrder(),
                        e == null ? null : e.confidence(),
                        e == null ? null : e.sourceEngine(), e == null ? null : e.sourceEngineVersion(),
                        elementIds, elementViolations, "textBlock");
            }
        }
        if (doc.tables() != null) {
            for (CanonicalDocumentDto.TableElement e : doc.tables()) {
                checkElement(doc, e == null ? null : e.elementId(), e == null ? null : e.elementType(),
                        e == null ? null : e.pageNumber(), e == null ? null : e.readingOrder(),
                        e == null ? null : e.confidence(),
                        e == null ? null : e.sourceEngine(), e == null ? null : e.sourceEngineVersion(),
                        elementIds, elementViolations, "table");
            }
        }
        if (doc.equations() != null) {
            for (CanonicalDocumentDto.EquationElement e : doc.equations()) {
                checkElement(doc, e == null ? null : e.elementId(), e == null ? null : e.elementType(),
                        e == null ? null : e.pageNumber(), e == null ? null : e.readingOrder(),
                        e == null ? null : e.confidence(),
                        e == null ? null : e.sourceEngine(), e == null ? null : e.sourceEngineVersion(),
                        elementIds, elementViolations, "equation");
            }
        }
        if (doc.figures() != null) {
            for (CanonicalDocumentDto.FigureElement e : doc.figures()) {
                checkElement(doc, e == null ? null : e.elementId(), e == null ? null : e.elementType(),
                        e == null ? null : e.pageNumber(), e == null ? null : e.readingOrder(),
                        e == null ? null : e.confidence(),
                        e == null ? null : e.sourceEngine(), e == null ? null : e.sourceEngineVersion(),
                        elementIds, elementViolations, "figure");
            }
        }
        violations.addAll(elementViolations);

        // sections reference elements; a dangling reference breaks chunk provenance
        if (doc.sections() != null) {
            for (CanonicalDocumentDto.SectionInfo s : doc.sections()) {
                if (s == null || isBlank(s.sectionId())) {
                    violations.add("section without sectionId");
                } else if (s.elementIds() != null) {
                    for (String ref : s.elementIds()) {
                        if (ref != null && !elementIds.contains(ref)) {
                            violations.add("section " + s.sectionId()
                                    + " references unknown element " + ref);
                        }
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new InvalidDocumentException(
                    "canonical document " + quote(doc.documentId()) + " failed validation: "
                            + String.join("; ", violations));
        }
    }

    private void checkElement(CanonicalDocumentDto doc, String elementId, String elementType,
                              Integer pageNumber, Integer readingOrder, Double confidence,
                              String sourceEngine, String sourceEngineVersion,
                              Set<String> seenIds, List<String> violations, String family) {
        String label = family + " " + quote(elementId);
        if (isBlank(elementId)) {
            violations.add(family + " without element_id");
            label = family + " <blank-id>";
        } else if (!seenIds.add(elementId)) {
            violations.add("duplicate element_id " + elementId);
        }
        if (isBlank(elementType)) {
            violations.add(label + ": element_type is required");
        }
        if (pageNumber == null) {
            violations.add(label + ": page_number is required");
        } else if (doc.pageCount() == null || pageNumber < 1 || pageNumber > doc.pageCount()) {
            violations.add(label + ": page_number " + pageNumber + " outside 1.." + doc.pageCount());
        }
        if (readingOrder == null || readingOrder < 0) {
            violations.add(label + ": reading_order must be >= 0 (was " + readingOrder + ")");
        }
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            violations.add(label + ": confidence " + confidence + " outside 0..1");
        }
        if (isBlank(sourceEngine)) {
            violations.add(label + ": source_engine is required");
        }
        if (isBlank(sourceEngineVersion)) {
            violations.add(label + ": source_engine_version is required");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /**
     * Byte-exact mirror of the parser's {@code CanonicalIdentity} (Master Spec
     * §19 — the two MUST stay in lockstep or every legit document would be
     * rejected): SHA-256 over
     * {@code "sha256:<checksum>|engine:<engine>|version:<engineVersion>"}
     * (components lowercased/stripped, null → ""), first 128 bits as a UUID
     * with version nibble 5 and RFC-4122 variant bits. Public since the
     * recovery-2026-09-13 session: cross-package integration tests (the IT
     * packages live under com.syllabai.it) also mint valid derived ids, and a
     * test-local copy would be a THIRD derivation implementation to keep in
     * lockstep — the exact drift risk this mirror exists to prevent.
     */
    public static String derivedDocumentId(String checksumHex, String engine,
                                            String engineVersion) {
        String material = "sha256:" + component(checksumHex)
                + "|engine:" + component(engine)
                + "|version:" + component(engineVersion);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0f) | 0x50); // version 5
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3f) | 0x80); // RFC 4122 variant
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(uuidBytes);
            return new java.util.UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String component(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
