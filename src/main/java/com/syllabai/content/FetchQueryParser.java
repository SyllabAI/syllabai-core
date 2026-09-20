package com.syllabai.content;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Fetch query parser (R4, plan §7 FETCH): regex over the
 * series/year/paper/qnum vocabulary — no LLM on the happy path, no vector
 * calls. The vocabulary is the canonical enum (§8.1): "Summer"/"June" map to
 * JUN, "January"/"Jan" to JAN, "October"/"November" to NOV; raw labels are
 * never part of resolution.
 *
 * <p>Grammar fragments recognized (case-insensitive, all optional):</p>
 * <ul>
 *   <li>full paper code: {@code 4CH1/2C}, {@code 4CH0-1CR} (slash or dash,
 *       optional spaces around the separator) → {@code paperCode};</li>
 *   <li>bare unit: {@code 1C}, {@code 2CR} as a standalone token → {@code unit}
 *       (resolved against the subject code + the 4CH0 legacy alias at SQL
 *       time, never here — the parser does not know the subject);</li>
 *   <li>series word: summer/june → JUN, january/jan → JAN,
 *       november/october → NOV;</li>
 *   <li>year: 4-digit 1900–2099 (first match);</li>
 *   <li>question number: {@code question 6}, {@code q4}, {@code Q7b} —
 *       optional single part letter a–h captured separately;</li>
 *   <li>intent hint: "answer/mark scheme/solution" ⇒ mark-scheme-seeking,
 *       "what did/what was/ask" ⇒ question-paper-seeking (the same probes the
 *       gold compiler used — tier ordering of QP vs MS evidence, plan §9).</li>
 * </ul>
 *
 * <p>The parser never interprets content words: everything it returns is
 * metadata the bank SQL can filter on. A query carrying none of the
 * vocabulary parses to an all-empty result and the caller treats it as a
 * parse defect (logged, vector fallback allowed per plan §7).</p>
 */
public final class FetchQueryParser {

    /** One parsed Fetch query — every field optional, filled only from the query text. */
    public record ParsedFetchQuery(String paperCode, String unit, String series,
                                   Integer year, Integer qnum, String part,
                                   boolean msSeeking, String normalized) {

        /** True when the query pinned a paper identity itself (strict resolution). */
        public boolean hasExplicitPaper() {
            return paperCode != null || unit != null;
        }

        /** True when nothing usable was parsed — the caller logs a parse defect. */
        public boolean isEmpty() {
            return paperCode == null && unit == null && series == null
                    && year == null && qnum == null;
        }
    }

    static final Pattern PAPER_CODE = Pattern.compile("\\b(4CH[01])\\s*[/-]\\s*([12]\\s*C\\s*R?)\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern BARE_UNIT = Pattern.compile("(?<![A-Za-z0-9])([12])\\s*(CR|C)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    static final Pattern SERIES_JUN = Pattern.compile("\\b(summer|june)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern SERIES_JAN = Pattern.compile("\\b(january|jan)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern SERIES_NOV = Pattern.compile("\\b(november|october)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern YEAR = Pattern.compile("\\b(19|20)(\\d{2})\\b");
    static final Pattern QNUM = Pattern.compile("\\b(?:question|q)\\.?\\s*(\\d{1,2})\\s*([a-h])?\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern MS_SEEKING = Pattern.compile("\\b(answer|mark\\s+scheme|solution|markscheme)\\b",
            Pattern.CASE_INSENSITIVE);
    static final Pattern QP_SEEKING = Pattern.compile("\\b(what did|what was|ask)\\b",
            Pattern.CASE_INSENSITIVE);

    private FetchQueryParser() {
    }

    /** Parses the query; never returns null — an unparseable query yields an empty parse. */
    public static ParsedFetchQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return new ParsedFetchQuery(null, null, null, null, null, null, false, "");
        }
        String q = query.strip();

        String paperCode = null;
        Matcher code = PAPER_CODE.matcher(q);
        if (code.find()) {
            paperCode = code.group(1).toUpperCase() + "/" + code.group(2).toUpperCase().replace(" ", "");
        }

        // bare unit only when no full code was matched ("paper 2C", "the 1CR paper")
        String unit = null;
        if (paperCode == null) {
            Matcher bare = BARE_UNIT.matcher(q);
            if (bare.find()) {
                unit = bare.group(1) + bare.group(2).toUpperCase();
            }
        }

        String series = null;
        if (SERIES_JUN.matcher(q).find()) {
            series = "JUN";
        } else if (SERIES_JAN.matcher(q).find()) {
            series = "JAN";
        } else if (SERIES_NOV.matcher(q).find()) {
            series = "NOV";
        }

        Integer year = null;
        Matcher y = YEAR.matcher(q);
        if (y.find()) {
            year = Integer.parseInt(y.group());
        }

        Integer qnum = null;
        String part = null;
        Matcher n = QNUM.matcher(q);
        if (n.find()) {
            qnum = Integer.parseInt(n.group(1));
            part = n.group(2) == null ? null : n.group(2).toLowerCase();
        }

        boolean msSeeking = MS_SEEKING.matcher(q).find() || !QP_SEEKING.matcher(q).find();

        String normalized = String.join(" ",
                Optional.ofNullable(paperCode).orElse(unit == null ? "" : "unit:" + unit),
                series == null ? "" : series,
                year == null ? "" : year.toString(),
                qnum == null ? "" : "Q" + qnum + (part == null ? "" : part))
                .trim();
        return new ParsedFetchQuery(paperCode, unit, series, year, qnum, part, msSeeking, normalized);
    }
}
