package com.syllabai.content;

import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.curriculum.CurriculumScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The deterministic Enumerate path (R4, plan §7 ENUMERATE): SQL first —
 * questions ⋈ question_topics / question_spec_points, year windows off the
 * V35 canonical {@code exam_papers.year}/{@code series} columns, plus marks
 * and question-type filters. Completeness comes from the SQL path; the card
 * semantic-fallback union (plan §7) layers on later and never replaces it.
 *
 * <p>Three shapes ship now:</p>
 * <ol>
 *   <li><strong>topic</strong> — "all questions about electrolysis": a
 *       knowledge-node title match drives {@code question_topics};</li>
 *   <li><strong>spec</strong> — "all questions for specification point
 *       4CH1-2.36": a knowledge-node code match drives
 *       {@code question_spec_points};</li>
 *   <li><strong>paper</strong> — "every question in the June 2014 paper
 *       4CH0/1C": the Fetch parser's series/year/code grammar resolves the
 *       paper, the bank returns its question list.</li>
 * </ol>
 *
 * <p>Set semantics mirror the ratified gold (gold-v1 compiler): topic/spec
 * questions are {@code provenance='PAST_PAPER'} and active; the paper axis is
 * active-policy agnostic (the C13 import ships paper-anchored rows
 * active=false — they are still the paper's question list); paper information
 * attaches via a LEFT JOIN that hides REJECTED paper rows (§8.2 superseded
 * duplicates) but keeps their questions listable — a superseded paper's
 * questions remain real bank content, they simply no longer claim the retired
 * paper identity. Results deduplicate on the plan's key
 * {@code (paper_code, qnum, kind)}.</p>
 */
@Service
public class EnumerateService {

    private static final Pattern SPEC_CODE = Pattern.compile("\\b(4CH[01]-[0-9][0-9A-Za-z.]*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPIC_TEXT = Pattern.compile(
            "^.*?(?:about|for)\\s+(.+?)(?:\\s+from\\s+\\d{4}\\s+to\\s+\\d{4})?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern YEAR_WINDOW = Pattern.compile(
            "\\bfrom\\s+(\\d{4})\\s+to\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);

    private static final int MAX_ROWS = 400;

    private final JdbcTemplate jdbc;

    public EnumerateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EnumeratedQuestion(UUID questionId, String externalRef, String stemExcerpt,
                                     Integer marks, String questionType,
                                     UUID paperId, String paperCode, String sessionLabel,
                                     String series, Integer year) {

        /** The plan §7 dedup key: one hit per (paper, atom, kind). */
        public String dedupKey() {
            return (paperCode == null ? "-" : paperCode) + "|"
                    + (externalRef == null ? "-" : externalRef.split("-")[0]) + "|bank";
        }
    }

    public record EnumerateResult(String mode, String resolvedNodeCode, String resolvedNodeTitle,
                                  Integer yearFrom, Integer yearTo, boolean ambiguous,
                                  List<EnumeratedQuestion> questions) {
    }

    /**
     * Enumerates by natural-language query. Supported shapes: paper-axis
     * ("every question in the … paper 4CH0/2C"), spec-point ("all questions
     * for specification point 4CH1-2.36"), topic ("list all questions about
     * …", with an optional "from YYYY to YYYY" window). A query none of whose
     * shapes match returns an honest empty result with the mode echo.
     */
    public EnumerateResult enumerate(String query, CurriculumScope scope) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("curriculum scope is mandatory — enumerate never runs unscoped (T-C07)");
        }
        String q = query.strip();

        // 1. paper axis — the Fetch grammar resolves code/series/year
        ParsedFetchQuery parsed = FetchQueryParser.parse(q);
        if (parsed.paperCode() != null && parsed.year() != null
                && Pattern.compile("\\bpaper\\b", Pattern.CASE_INSENSITIVE).matcher(q).find()) {
            return enumeratePaper(parsed, scope);
        }

        // 2. spec-point axis — explicit statement code in the text
        Matcher spec = SPEC_CODE.matcher(q);
        if (spec.find()) {
            return enumerateByNode(spec.group(1), null, window(q), scope, "spec");
        }

        // 3. topic axis — "… about <topic>" with optional year window
        Matcher topic = TOPIC_TEXT.matcher(q);
        if (topic.matches()) {
            String text = topic.group(1).strip();
            if (!text.isBlank() && text.length() >= 3) {
                return enumerateByNode(null, text, window(q), scope, "topic");
            }
        }
        return new EnumerateResult("unparsed", null, null, null, null, false, List.of());
    }

    /** Structured enumeration (no NL parse): node code or title + filters. */
    public EnumerateResult enumerateStructured(String nodeCode, String nodeTitle,
                                               Integer yearFrom, Integer yearTo,
                                               Integer marks, String questionType,
                                               boolean specAxis, CurriculumScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("curriculum scope is mandatory — enumerate never runs unscoped (T-C07)");
        }
        return enumerateByNode(nodeCode, nodeTitle, new int[] {
                yearFrom == null ? Integer.MIN_VALUE : yearFrom,
                yearTo == null ? Integer.MAX_VALUE : yearTo}, scope,
                specAxis ? "spec" : "topic", marks, questionType);
    }

    private EnumerateResult enumeratePaper(ParsedFetchQuery parsed, CurriculumScope scope) {
        List<PaperIdRow> papers = jdbc.query("""
                select ep.id, ep.paper_code, ep.session_label, ep.series, ep.year
                from exam_papers ep
                join subjects s on s.id = ep.subject_id
                where ep.validation_state <> 'REJECTED'
                  and s.curriculum_version_id = ?
                  and ep.paper_code = ?
                  and ep.year = ?
                  and (?::varchar(3) is null or ep.series = ?::varchar(3))
                order by ep.paper_code, ep.session_label
                """, (rs, i) -> new PaperIdRow(rs.getObject("id", UUID.class), rs.getString("paper_code"),
                        rs.getString("session_label"), rs.getString("series"),
                        (Integer) rs.getObject("year")),
                scope.curriculumVersionId(), parsed.paperCode(), parsed.year(),
                parsed.series(), parsed.series());
        if (papers.isEmpty()) {
            return new EnumerateResult("paper", parsed.paperCode(), null, null, null, false, List.of());
        }
        // deterministic: exactly one live paper per (code, series, year) — the
        // §8.2 duplicate guard (V35) enforces this invariant at the data layer
        PaperIdRow paper = papers.get(0);
        // Active-policy AGNOSTIC (gold-v1 enumerate_paper semantics: "bank rows,
        // active-policy agnostic" — the C13 import ships paper-anchored rows
        // active=false; they are still the paper's question list)
        List<EnumeratedQuestion> rows = jdbc.query("""
                select q.id, q.external_ref, q.stem, q.marks, q.question_type
                from questions q
                where q.exam_paper_id = ? and q.provenance = 'PAST_PAPER'
                order by q.external_ref
                """, (rs, i) -> new EnumeratedQuestion(rs.getObject("id", UUID.class),
                        rs.getString("external_ref"), excerpt(rs.getString("stem")),
                        (Integer) rs.getObject("marks"), rs.getString("question_type"),
                        paper.id(), paper.paperCode(), paper.sessionLabel(),
                        paper.series(), paper.year()),
                paper.id());
        boolean ambiguous = papers.size() > 1;
        return new EnumerateResult("paper", paper.paperCode(), paper.sessionLabel(),
                parsed.year(), parsed.year(), ambiguous, dedup(rows));
    }

    private record PaperIdRow(UUID id, String paperCode, String sessionLabel, String series,
                              Integer year) {
    }

    private EnumerateResult enumerateByNode(String nodeCode, String nodeTitle, int[] window,
                                            CurriculumScope scope, String mode) {
        return enumerateByNode(nodeCode, nodeTitle, window, scope, mode, null, null);
    }

    private EnumerateResult enumerateByNode(String nodeCode, String nodeTitle, int[] window,
                                            CurriculumScope scope, String mode,
                                            Integer marks, String questionType) {
        List<NodeRow> nodes = jdbc.query("""
                select kn.id, kn.code, kn.title
                from knowledge_nodes kn
                where (?::text is null or lower(kn.code) = lower(?::text))
                  and (?::text is null or lower(kn.title) = lower(?::text))
                order by kn.code
                limit 1
                """, (rs, i) -> new NodeRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("title")),
                nodeCode, nodeCode, nodeTitle, nodeTitle);
        if (nodes.isEmpty()) {
            return new EnumerateResult(mode, nodeCode, nodeTitle, null, null, false, List.of());
        }
        NodeRow node = nodes.get(0);
        String fromYear = window[0] == Integer.MIN_VALUE ? null : String.valueOf(window[0]);
        String toYear = window[1] == Integer.MAX_VALUE ? null : String.valueOf(window[1]);
        String sql = """
                select q.id, q.external_ref, q.stem, q.marks, q.question_type,
                       ep.id paper_id, ep.paper_code, ep.session_label, ep.series, ep.year
                from %s
                join questions q on q.id = %s and q.provenance = 'PAST_PAPER' and q.active
                join knowledge_nodes kn on kn.id = %s
                left join exam_papers ep on ep.id = q.exam_paper_id
                     and ep.validation_state <> 'REJECTED'
                where kn.id = ?
                  and (?::int is null or ep.year >= ?::int)
                  and (?::int is null or ep.year <= ?::int)
                  and (?::int is null or q.marks = ?::int)
                  and (?::text is null or q.question_type = ?::text)
                order by ep.year nulls last, ep.series nulls last, ep.paper_code nulls last,
                         q.external_ref nulls last
                limit ?
                """.formatted(axisTable(mode), "axis.question_id", axisNodeColumn(mode));
        List<EnumeratedQuestion> rows = jdbc.query(sql, (rs, i) -> new EnumeratedQuestion(
                        rs.getObject("id", UUID.class), rs.getString("external_ref"),
                        excerpt(rs.getString("stem")), (Integer) rs.getObject("marks"),
                        rs.getString("question_type"), rs.getObject("paper_id", UUID.class),
                        rs.getString("paper_code"), rs.getString("session_label"),
                        rs.getString("series"), (Integer) rs.getObject("year")),
                node.id(), fromYear, fromYear, toYear, toYear, marks, marks, questionType, questionType,
                MAX_ROWS);
        return new EnumerateResult(mode, node.code(), node.title(),
                fromYear == null ? null : Integer.valueOf(fromYear),
                toYear == null ? null : Integer.valueOf(toYear),
                false, dedup(rows));
    }

    /** topic walks question_topics, spec walks the ratified question_spec_points mapping. */
    private static String axisTable(String mode) {
        return "spec".equals(mode) ? "question_spec_points axis" : "question_topics axis";
    }

    private static String axisNodeColumn(String mode) {
        return "spec".equals(mode) ? "axis.spec_point_node_id" : "axis.node_id";
    }

    /** Plan §7 dedup rule: one hit per (paper_code, atom_number, kind). */
    private static List<EnumeratedQuestion> dedup(List<EnumeratedQuestion> rows) {
        Map<String, EnumeratedQuestion> byKey = new LinkedHashMap<>();
        for (EnumeratedQuestion row : rows) {
            byKey.putIfAbsent(row.dedupKey(), row);
        }
        return List.copyOf(byKey.values());
    }

    private static String excerpt(String stem) {
        if (stem == null) {
            return null;
        }
        String stripped = stem.strip();
        return stripped.length() <= 240 ? stripped : stripped.substring(0, 240) + "…";
    }

    private static int[] window(String query) {
        Matcher w = YEAR_WINDOW.matcher(query);
        if (w.find()) {
            return new int[] {Integer.parseInt(w.group(1)), Integer.parseInt(w.group(2))};
        }
        return new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE};
    }

    private record NodeRow(UUID id, String code, String title) {
    }
}
