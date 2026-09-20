package com.syllabai.content;

import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.curriculum.CurriculumScope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The deterministic Fetch path (R4, plan §7 FETCH): parse → bank SQL →
 * assemble QP text + MS points by ID. <strong>Zero vector calls on the happy
 * path</strong> — resolution is pure metadata (series/year/paper/qnum) served
 * from the bank tables (V35 canonical {@code series}/{@code year} columns).
 *
 * <p>Resolution contract:</p>
 * <ul>
 *   <li>papers resolve within the caller's curriculum scope (T-C07) and never
 *       serve REJECTED rows — §8.2 superseded duplicates are invisible;</li>
 *   <li>an explicit paper code pins candidates; a bare unit resolves against
 *       every subject code of the scope plus the 4CH0 legacy alias; no paper
 *       reference means every (series, year) paper of the scope — multiple
 *       candidates mark the result {@code ambiguous} (deterministic order,
 *       the surface narrows, we never guess);</li>
 *   <li>the question inside a resolved paper is matched by the
 *       {@code external_ref} atom prefix ({@code q01-…}, zero-padding
 *       tolerant); the mark scheme is the latest version's points in order;</li>
 *   <li>a query that parses to nothing is a parse defect (logged, returned
 *       honestly as empty with the echo) — the caller may fall back to
 *       evidence-layer vector search per plan §7.</li>
 * </ul>
 */
@Service
public class FetchService {

    private static final Logger LOG = LoggerFactory.getLogger(FetchService.class);

    private static final int MAX_PAPERS = 6;
    private static final int MAX_MARK_POINTS = 60;

    private final JdbcTemplate jdbc;

    public FetchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MarkPointView(String ref, String text, Integer marks) {
    }

    public record PartView(String label, String prompt, Integer marks) {
    }

    public record FetchQuestion(UUID questionId, String externalRef, String stem, Integer marks,
                                String questionType, String commandWord,
                                List<PartView> parts, List<MarkPointView> markPoints) {
    }

    public record FetchPaperHit(UUID paperId, String paperCode, String sessionLabel,
                                String series, Integer year, String validationState,
                                String qpDocumentId, String msDocumentId,
                                FetchQuestion question) {
    }

    public record FetchResult(ParsedFetchQuery parsed, boolean ambiguous, boolean parseDefect,
                              List<FetchPaperHit> papers) {
    }

    /** Deterministic Fetch: parse → resolve → assemble, no vector calls. */
    public FetchResult fetch(String query, CurriculumScope scope) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("curriculum scope is mandatory — fetch never runs unscoped (T-C07)");
        }
        ParsedFetchQuery parsed = FetchQueryParser.parse(query);
        if (parsed.isEmpty()) {
            LOG.warn("fetch parse defect: no recognizable metadata vocabulary in query [{}]", query);
            return new FetchResult(parsed, false, true, List.of());
        }

        List<String> codes = candidateCodes(parsed, scope);
        List<PaperRow> papers = resolvePapers(parsed, codes, scope);
        List<FetchPaperHit> hits = new ArrayList<>();
        int resolved = 0;
        for (PaperRow paper : papers) {
            FetchQuestion question = parsed.qnum() == null ? null : resolveQuestion(paper.id(), parsed);
            if (question != null) {
                resolved++;
            }
            hits.add(new FetchPaperHit(paper.id(), paper.paperCode(), paper.sessionLabel(),
                    paper.series(), paper.year(), paper.validationState(),
                    paper.qpDocumentId(), paper.msDocumentId(), question));
        }
        boolean ambiguous = resolved > 1 || (parsed.qnum() == null && papers.size() > 1);
        return new FetchResult(parsed, ambiguous, false, List.copyOf(hits));
    }

    /**
     * Candidate paper codes: explicit code wins; a bare unit resolves against
     * every subject code in the scope plus the 4CH0 legacy alias (pre-2016
     * papers are 4CH0-coded while the pilot subject is 4CH1 — prod truth,
     * bridge alias decision Task 31).
     */
    private List<String> candidateCodes(ParsedFetchQuery parsed, CurriculumScope scope) {
        if (parsed.paperCode() != null) {
            return List.of(parsed.paperCode());
        }
        if (parsed.unit() == null) {
            return List.of();
        }
        List<String> subjectCodes = jdbc.query("""
                select distinct s.code from subjects s
                where s.curriculum_version_id = ? and s.code is not null
                order by s.code
                """, (rs, i) -> rs.getString(1), scope.curriculumVersionId());
        // deterministic: subject codes first (alphabetical), legacy alias last
        List<String> codes = new ArrayList<>(subjectCodes.stream()
                .map(c -> c + "/" + parsed.unit()).toList());
        codes.add("4CH0/" + parsed.unit());
        return codes.stream().distinct().toList();
    }

    private record PaperRow(UUID id, String paperCode, String sessionLabel, String series,
                            Integer year, String validationState, String qpDocumentId,
                            String msDocumentId) {
    }

    private List<PaperRow> resolvePapers(ParsedFetchQuery parsed, List<String> codes,
                                         CurriculumScope scope) {
        if (parsed.year() == null) {
            return List.of();   // a year is the minimum anchor for a deterministic fetch
        }
        String codeFilter = codes.isEmpty() ? null
                : "{" + String.join(",", codes.stream().map(c -> '"' + c + '"').toList()) + "}";
        return jdbc.query("""
                select ep.id, ep.paper_code, ep.session_label, ep.series, ep.year,
                       ep.validation_state, ep.question_paper_document_id, ep.mark_scheme_document_id
                from exam_papers ep
                join subjects s on s.id = ep.subject_id
                where ep.validation_state <> 'REJECTED'
                  and s.curriculum_version_id = ?
                  and ep.year = ?
                  and (?::varchar(3) is null or ep.series = ?::varchar(3))
                  and (?::text[] is null or ep.paper_code = any(?::text[]))
                order by ep.paper_code, ep.session_label
                limit ?
                """, (rs, i) -> new PaperRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("paper_code"),
                        rs.getString("session_label"),
                        rs.getString("series"),
                        (Integer) rs.getObject("year"),
                        rs.getString("validation_state"),
                        rs.getString("question_paper_document_id"),
                        rs.getString("mark_scheme_document_id")),
                scope.curriculumVersionId(), parsed.year(),
                parsed.series(), parsed.series(),
                codeFilter, codeFilter,
                MAX_PAPERS);
    }

    private FetchQuestion resolveQuestion(UUID paperId, ParsedFetchQuery parsed) {
        // external_ref atom prefix: 'q01-<hash>' / 'q1-<hash>' — zero-padding tolerant,
        // never a prefix collision ('q1-' does not match 'q10-…' because '-' anchors)
        String refPattern = "^q0*" + parsed.qnum() + "-.*";
        List<FetchQuestion> hits = jdbc.query("""
                select q.id, q.external_ref, q.stem, q.marks, q.question_type, q.command_word
                from questions q
                where q.exam_paper_id = ? and q.active and q.external_ref ~ ?
                order by q.external_ref
                limit 1
                """, (rs, i) -> new FetchQuestion(
                        rs.getObject("id", UUID.class),
                        rs.getString("external_ref"),
                        rs.getString("stem"),
                        (Integer) rs.getObject("marks"),
                        rs.getString("question_type"),
                        rs.getString("command_word"),
                        parts(rs.getObject("id", UUID.class)),
                        markPoints(rs.getObject("id", UUID.class))),
                paperId, refPattern);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private List<PartView> parts(UUID questionId) {
        return jdbc.query("""
                select qp.label, qp.prompt, qp.marks
                from question_parts qp
                join question_versions qv on qv.id = qp.question_version_id
                where qv.question_id = ?
                  and qv.version = (select max(version) from question_versions where question_id = ?)
                order by qp.ordering
                """, (rs, i) -> new PartView(rs.getString("label"), rs.getString("prompt"),
                        (Integer) rs.getObject("marks")),
                questionId, questionId);
    }

    private List<MarkPointView> markPoints(UUID questionId) {
        return jdbc.query("""
                select mp.ref, mp.text, mp.marks
                from mark_points mp
                join mark_schemes ms on ms.id = mp.mark_scheme_id
                join question_versions qv on qv.id = ms.question_version_id
                where qv.question_id = ?
                  and qv.version = (select max(version) from question_versions where question_id = ?)
                  and ms.validation_state <> 'REJECTED'
                order by ms.version_label, mp.ordering
                limit ?
                """, (rs, i) -> new MarkPointView(rs.getString("ref"), rs.getString("text"),
                        (Integer) rs.getObject("marks")),
                questionId, questionId, MAX_MARK_POINTS);
    }
}
