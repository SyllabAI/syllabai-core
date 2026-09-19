package com.syllabai.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic per-chunk retrieval header (Embedding v2, plan §4.1 step 4 /
 * §4.2 grammar). The chunker stamps EVERY chunk with a header line built from
 * document identity + the chunk's atom group key + page range — chunks 2..n of
 * an atom embed with the same paper context as chunk 1, which is what makes
 * per-chunk vectors self-describing. The header is prepended to the chunk text
 * before embedding (so the vector sees it) while the filterable identity lives
 * in metadata columns, never parsed back out of the header in SQL.
 *
 * <p>Grammar (pipe-delimited, single line; empty segments are skipped):</p>
 * <pre>
 *   &lt;subject qual/code&gt; | &lt;series year&gt; | &lt;paper code&gt; | &lt;Spec range&gt; |
 *   &lt;label&gt; | &lt;unit/topic&gt; | &lt;Q ref&gt; | &lt;pp range&gt;
 * </pre>
 *
 * <p>Plan examples this composes to:</p>
 * <ul>
 *   <li>QP:   {@code IGCSE Chemistry 4CH1 | Jun 2022 | 1C | Q3 | pp.5–6}</li>
 *   <li>MS:   {@code IGCSE Chemistry 4CH1 | Jun 2022 | 1C | MS Q3 | pp.3–4}</li>
 *   <li>note: {@code IGCSE Chemistry | Notes | Spec 1.22–1.25 | Electrolysis}</li>
 * </ul>
 *
 * <p>Deterministic: no clocks, no randomness, no locale-dependent formatting
 * (the en dash and literal strings are fixed constants). Documents without any
 * identity material get an empty header and their content is left untouched —
 * legacy-shaped documents chunk exactly as before.</p>
 */
final class ChunkHeaderBuilder {

    private ChunkHeaderBuilder() {
    }

    /**
     * @param kind      document kind (may be null for legacy callers)
     * @param meta      doc-level retrieval identity (may be null)
     * @param groupKey  atom identity of the chunk's blocks (may be null)
     * @param pageStart first page of the chunk (may be null)
     * @param pageEnd   last page of the chunk (may be null)
     * @return the header line, or "" when there is no identity material
     */
    static String build(Document.Kind kind, CanonicalDocumentDto.RetrievalMeta meta,
                        String groupKey, Integer pageStart, Integer pageEnd) {
        List<String> segments = new ArrayList<>();
        if (meta != null) {
            String subject = subjectSegment(meta);
            if (!subject.isBlank()) {
                segments.add(subject);
            }
            String seriesYear = seriesYearSegment(meta);
            if (!seriesYear.isBlank()) {
                segments.add(seriesYear);
            }
            if (notBlank(meta.paperCode())) {
                segments.add(meta.paperCode().strip());
            }
            if (notBlank(meta.label())) {
                segments.add(meta.label().strip());
            }
            String specRange = specRangeSegment(meta.specCodes());
            if (!specRange.isBlank()) {
                segments.add(specRange);
            }
            if (notBlank(meta.unit())) {
                segments.add(meta.unit().strip());
            }
        }
        String qRef = qRefSegment(kind, meta, groupKey);
        if (!qRef.isBlank()) {
            segments.add(qRef);
        }
        if (segments.isEmpty()) {
            // no identity material at all (legacy-shaped document) — a bare page
            // range is not a header; content stays byte-identical to legacy shape
            return "";
        }
        String pages = pagesSegment(pageStart, pageEnd);
        if (!pages.isBlank()) {
            segments.add(pages);
        }
        return String.join(" | ", segments);
    }

    /** "IGCSE Chemistry 4CH1" / "IGCSE Chemistry" / "4CH1". */
    private static String subjectSegment(CanonicalDocumentDto.RetrievalMeta meta) {
        String title = blankToNull(meta.subjectTitle());
        String code = blankToNull(meta.subjectCode());
        if (title != null && code != null) {
            return title.strip() + " " + code.strip();
        }
        return title != null ? title.strip() : code != null ? code.strip() : "";
    }

    /** "Jun 2022" / "2022" (canonical series enum only; validated upstream). */
    private static String seriesYearSegment(CanonicalDocumentDto.RetrievalMeta meta) {
        if (meta.year() == null) {
            return "";
        }
        return displaySeries(meta.series()) + " " + meta.year();
    }

    static String displaySeries(String series) {
        if ("JAN".equals(series)) {
            return "Jan";
        }
        if ("JUN".equals(series)) {
            return "Jun";
        }
        if ("NOV".equals(series)) {
            return "Nov";
        }
        return "";
    }

    /** "Spec 1.22" / "Spec 1.22–1.25" — numeric-aware ordering (1.10 &lt; 1.2). */
    static String specRangeSegment(List<String> specCodes) {
        if (specCodes == null || specCodes.isEmpty()) {
            return "";
        }
        List<String> codes = specCodes.stream()
                .filter(ChunkHeaderBuilder::notBlank)
                .map(s -> s.strip())
                .distinct()
                .sorted(specCodeOrder())
                .toList();
        if (codes.isEmpty()) {
            return "";
        }
        return codes.size() == 1
                ? "Spec " + codes.get(0)
                : "Spec " + codes.get(0) + "–" + codes.get(codes.size() - 1);
    }

    /** Numeric-aware spec-code comparator: dot-separated integer parts. */
    static Comparator<String> specCodeOrder() {
        return (a, b) -> {
            int[] pa = parts(a);
            int[] pb = parts(b);
            int n = Math.min(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                if (pa[i] != pb[i]) {
                    return Integer.compare(pa[i], pb[i]);
                }
            }
            return Integer.compare(pa.length, pb.length);
        };
    }

    private static int[] parts(String code) {
        String[] raw = code.strip().split("\\.");
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i].matches("\\d+") ? Integer.parseInt(raw[i]) : 0;
        }
        return out;
    }

    /** "Q3" / "MS Q3" — the mark scheme prefix lives in the q segment (plan §4.2). */
    private static String qRefSegment(Document.Kind kind,
                                      CanonicalDocumentDto.RetrievalMeta meta,
                                      String groupKey) {
        String atom = normalizeAtom(groupKey);
        if (atom == null) {
            return "";
        }
        boolean explicitLabel = meta != null && notBlank(meta.label());
        String prefix = kind == Document.Kind.MARK_SCHEME && !explicitLabel ? "MS " : "";
        return prefix + "Q" + atom;
    }

    /** "q3"/"Q3"/"3" → "3"; anything else is kept verbatim minus whitespace. */
    static String normalizeAtom(String groupKey) {
        if (!notBlank(groupKey)) {
            return null;
        }
        String trimmed = groupKey.strip();
        String lowered = trimmed.toLowerCase().startsWith("q") ? trimmed.substring(1).strip()
                : trimmed;
        return lowered.isBlank() ? null : lowered;
    }

    /** "pp.5–6" / "p.5". */
    private static String pagesSegment(Integer pageStart, Integer pageEnd) {
        if (pageStart == null) {
            return "";
        }
        if (pageEnd == null || pageEnd.intValue() == pageStart.intValue()) {
            return "p." + pageStart;
        }
        return "pp." + pageStart + "–" + pageEnd;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }
}
