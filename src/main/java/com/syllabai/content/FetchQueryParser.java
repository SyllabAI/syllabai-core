package com.syllabai.content;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   <li>question number, keyword-led: {@code question 6}, {@code q4},
 *       {@code Q7b}, {@code question number 3}, {@code question no. 10},
 *       {@code question 10th}, {@code question ten}, {@code question tenth
 *       part b} — optional single part letter a–h captured separately;</li>
 *   <li>question number, ordinal-led: {@code 10th question},
 *       {@code tenth question} — the number binds, the part letter does not
 *       (the identity binds on the number alone);</li>
 *   <li>word numbers one–forty-nine in cardinal and ordinal form
 *       ("tenth", "twenty one", "thirty-first"); letter-adjacent hyphens are
 *       folded to spaces before parsing ("twenty-one" → "twenty one") and the
 *       query is NFKC-normalized first, so full-width digits
 *       ("question １０") bind like ASCII ones — the H1 paraphrase audit
 *       (2026-09-28) showed the digit-only grammar let phrased identities
 *       bypass the fail-open paper guard as "not a paper ask";</li>
 *   <li>intent hint: "answer/mark scheme/solution" ⇒ mark-scheme-seeking,
 *       "what did/what was/ask" ⇒ question-paper-seeking (the same probes the
 *       gold compiler used — tier ordering of QP vs MS evidence, plan §9).</li>
 * </ul>
 *
 * <p>The parser never interprets content words: everything it returns is
 * metadata the bank SQL can filter on. A query carrying none of the
 * vocabulary parses to an all-empty result and the caller treats it as a
 * parse defect (logged, vector fallback allowed per plan §7).</p>
 *
 * <p>Guard posture note (H1 audit): widening the question-number grammar
 * widens what counts as a <em>stated paper identity</em>, so an ask like
 * "the first question is about electrolysis in june 2019" now parses as a
 * (possibly accidental) identity and fails closed through the paper guard
 * instead of serving generic retrieval. The over-refusal direction is the
 * deliberate trade — honest echo beats wrong-paper confidence.</p>
 */
public final class FetchQueryParser {

    /** One parsed Fetch query — every field optional, filled only from the query text.
     *
     *  @param partRoman the roman sub-part numeral ("ii"), bound ONLY when a part
     *                   letter also bound (Edexcel prints romans under a letter:
     *                   "(b)(ii)") — see {@link #QNUM}. Null when absent/unbound. */
    public record ParsedFetchQuery(String paperCode, String unit, String series,
                                   Integer year, Integer qnum, String part,
                                   boolean msSeeking, String normalized, String partRoman) {

        /** Old arity kept for existing callers and tests — roman unbound. */
        public ParsedFetchQuery(String paperCode, String unit, String series,
                                Integer year, Integer qnum, String part,
                                boolean msSeeking, String normalized) {
            this(paperCode, unit, series, year, qnum, part, msSeeking, normalized, null);
        }

        /** True when the query pinned a paper identity itself (strict resolution). */
        public boolean hasExplicitPaper() {
            return paperCode != null || unit != null;
        }

        /** True when nothing usable was parsed — the caller logs a parse defect. */
        public boolean isEmpty() {
            return paperCode == null && unit == null && series == null
                    && year == null && qnum == null;
        }

        /** The full part atom ref ("9-b-ii"), or the letter ref ("9-b"), or null —
         *  the filterable identity shape the resolver's atom column can carry. */
        public String partAtom() {
            if (qnum == null || part == null) {
                return null;
            }
            return qnum + "-" + part + (partRoman == null ? "" : "-" + partRoman);
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

    /**
     * Word numbers bound as question references — cardinals and ordinals,
     * one to forty-nine. Keyed lowercase; the space form is canonical because
     * letter-adjacent hyphens are folded to spaces during normalization.
     */
    private static final Map<String, Integer> WORD_QNUMS = buildWordQnums();

    private static Map<String, Integer> buildWordQnums() {
        Map<String, Integer> m = new HashMap<>();
        String[] card = {"one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
                "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        String[] ord = {"first", "second", "third", "fourth", "fifth", "sixth",
                "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
                "thirteenth", "fourteenth", "fifteenth", "sixteenth",
                "seventeenth", "eighteenth", "nineteenth"};
        for (int i = 0; i < card.length; i++) {
            m.put(card[i], i + 1);
            m.put(ord[i], i + 1);
        }
        String[] tens = {"twenty", "thirty", "forty"};
        String[] tensOrd = {"twentieth", "thirtieth", "fortieth"};
        for (int t = 0; t < tens.length; t++) {
            m.put(tens[t], 20 + 10 * t);
            m.put(tensOrd[t], 20 + 10 * t);
            for (int u = 0; u < 9; u++) {
                m.put(tens[t] + " " + card[u], 21 + 10 * t + u);
                m.put(tens[t] + " " + ord[u], 21 + 10 * t + u);
            }
        }
        return Map.copyOf(m);
    }

    /** The word-number vocabulary as one regex alternation, longest-first. */
    private static final String WORD_QNUM_ALT = WORD_QNUMS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));

    /**
     * Question number led by the keyword: "question 10", "q4", "Q7b",
     * "question number 3", "question no. 10", "question 10th", "question ten",
     * "question tenth part b", "9(b)", "9(b)(ii)". Group 1 captures ASCII
     * digits (ordinal suffix tolerated outside the group), group 2 a word
     * number, group 3 the part letter a–h (optionally parenthesized — the
     * paper prints "(b)"), groups 4/5 an optional roman sub-part numeral
     * bound ONLY as an extension of the letter — parenthesized with a
     * mandatory close ("(ii)", so "(i think" can never bind) or bare after
     * whitespace ("part b ii"). A bare roman without a letter never binds:
     * "question 9 (i think)" and "question 9 (ii)" bind the number alone
     * (Edexcel prints romans under a letter part; the pronoun/false-positive
     * risk outweighs the shorthand). The code-side guard in {@link #parse}
     * enforces the letter requirement regardless of what the regex matched,
     * and the tail lookahead (not a word boundary) keeps the parenthesized
     * forms from backtracking away.
     */
    static final Pattern QNUM = Pattern.compile(
            "\\b(?:question|q)\\.?\\s*(?:(?:number|no)\\.?\\s*)?"
                    + "(?:(\\d{1,2})(?:st|nd|rd|th)?|(" + WORD_QNUM_ALT + "))"
                    + "\\s*(?:part\\s*)?(?:[(]?([a-h])[)]?"
                    + "(?:\\s*\\((i{1,3}|iv|v|vi{1,3}|ix|x)\\)"
                    + "|\\s+(i{1,3}|iv|v|vi{1,3}|ix|x)\\b)?(?![a-z0-9])"
                    + "|\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Question number led by its ordinal: "10th question", "tenth question".
     * The part letter is not bound on these forms — the identity binds on the
     * number alone.
     */
    static final Pattern QNUM_LEADING_ORDINAL = Pattern.compile(
            "\\b(?:(\\d{1,2})(?:st|nd|rd|th)|(" + WORD_QNUM_ALT + "))\\s+(?:question|q)\\b",
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
        // NFKC folds full-width digits/letters ("question １０") to ASCII; the
        // letter-adjacent hyphen fold turns "twenty-one" into the map's
        // canonical "twenty one" without touching "4CH0-1C" (digit-adjacent).
        String q = Normalizer.normalize(query.strip(), Normalizer.Form.NFKC);
        q = q.replaceAll("(?<=\\p{L})-(?=\\p{L})", " ");

        String paperCode = null;
        Matcher code = PAPER_CODE.matcher(q);
        if (code.find()) {
            paperCode = code.group(1).toUpperCase(Locale.ROOT) + "/" + code.group(2).toUpperCase(Locale.ROOT).replace(" ", "");
        }

        // bare unit only when no full code was matched ("paper 2C", "the 1CR paper")
        String unit = null;
        if (paperCode == null) {
            Matcher bare = BARE_UNIT.matcher(q);
            if (bare.find()) {
                unit = bare.group(1) + bare.group(2).toUpperCase(Locale.ROOT);
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
        String partRoman = null;
        Matcher n = QNUM.matcher(q);
        if (n.find()) {
            qnum = n.group(1) != null
                    ? Integer.valueOf(n.group(1))
                    : WORD_QNUMS.get(n.group(2).toLowerCase(Locale.ROOT));
            part = n.group(3) == null ? null : n.group(3).toLowerCase(Locale.ROOT);
            // letter-gated roman: a roman numeral is a sub-part identity only
            // under a bound part letter (see QNUM javadoc); the parenthesized
            // and bare forms land in different groups and coalesce here
            String romanRaw = n.group(4) != null ? n.group(4) : n.group(5);
            partRoman = (part != null && romanRaw != null)
                    ? romanRaw.toLowerCase(Locale.ROOT) : null;
        } else {
            Matcher o = QNUM_LEADING_ORDINAL.matcher(q);
            if (o.find()) {
                qnum = o.group(1) != null
                        ? Integer.valueOf(o.group(1))
                        : WORD_QNUMS.get(o.group(2).toLowerCase(Locale.ROOT));
            }
        }

        boolean msSeeking = MS_SEEKING.matcher(q).find() || !QP_SEEKING.matcher(q).find();

        String normalized = String.join(" ",
                Optional.ofNullable(paperCode).orElse(unit == null ? "" : "unit:" + unit),
                series == null ? "" : series,
                year == null ? "" : year.toString(),
                qnum == null ? "" : "Q" + qnum
                        + (part == null ? "" : part + (partRoman == null ? "" : "-" + partRoman)))
                .trim();
        return new ParsedFetchQuery(paperCode, unit, series, year, qnum, part, msSeeking,
                normalized, partRoman);
    }
}
