package com.syllabai.teacher.ingestion;

import com.syllabai.teacher.ingestion.GlmOcrMarkSchemeDraftDto.MarkPoint;
import com.syllabai.teacher.ingestion.GlmOcrMarkSchemeDraftDto.MarkSchemeEntry;
import com.syllabai.teacher.ingestion.GlmOcrPaperDraftDto.QuestionDraft;
import com.syllabai.teacher.ingestion.GlmOcrReconciliationDto.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic, side-effect-free adapter from the GLM-OCR parser draft contract
 * onto the existing T-011 {@code PastPaperDraftDto} (Master Spec §27 — schemas are
 * shared, never Java dependencies; this mapper is the ONLY translation point, so
 * field semantics stay in one auditable place).
 *
 * <p>Mapping decisions (all documented in docs/t-c02-bridge.md, nothing inferred):
 * <ul>
 *   <li><strong>paper identity</strong> comes from the MS draft — the real corpus MS
 *       carries board/qualification/paperReference/logNumber/publicationCode/session
 *       while the QP front matter yielded none. Subject is NOT inferred from the
 *       paper code; T-011 resolves an honest generic subject, teachers remap in
 *       review.</li>
 *   <li><strong>mark points</strong>: MS entries split on "(N)" markers become one
 *       point each; entries whose answer cell carried no markers (MCQ rationale
 *       rows, unsplit cells) become ONE whole-entry point so every question keeps
 *       its marking evidence — a question with no rows at all would be silent loss.
 *       Unknown marks (null) materialize as 0 (unknown), never a guess.</li>
 *   <li><strong>part references</strong>: MS labels "13(b)(i)" become refs
 *       "13-b-i", matching the QP part-label convention ("b-i") — a mechanical
 *       re-formatting of the same printed structure, not an inference. Unmatched
 *       refs attach at question level with the ref preserved.</li>
 *   <li><strong>MCQ options / QWC / guidance / IC table / figure refs / warnings /
 *       numbering style / answer prompts / marks-known states / log-publication
 *       identifiers</strong> are not representable in the T-011 DTO — the bridge
 *       record persists the verbatim drafts (JSONB) so nothing is discarded.</li>
 *   <li><strong>command words</strong> are NOT guessed from stems (the GLM-OCR
 *       extractor reports none).</li>
 * </ul></p>
 */
@Component
public class GlmOcrDraftMapper {

    static final String BRIDGE_METHOD = "glm-ocr-qp-v1+glm-ocr-ms-v1";

    /** MS part string "bi" (parens already stripped) → QP label convention "b-i". */
    private static final Pattern LETTER_THEN_ROMAN = Pattern.compile("^([a-h])([ivx]+)$");

    /**
     * GLM-OCR QP + MS drafts → the existing T-011 past-paper draft. The result is
     * fed straight into {@code PastPaperIngestionService.ingest} — every SUGGESTED
     * validation guarantee of that path applies unchanged.
     */
    public PastPaperDraftDto toPastPaperDraft(GlmOcrPaperDraftDto qpDraft,
                                              GlmOcrMarkSchemeDraftDto msDraft) {
        GlmOcrPaperDraftDto.PaperMeta qpMeta = qpDraft.paper();
        GlmOcrPaperDraftDto.PaperMeta msMeta = msDraft.paper();

        // Session and paper reference come from the QUESTION PAPER side first: the
        // exam paper is the authority on its own session. MS covers of newer series
        // print the PUBLICATION month, not the session (the June-2020 MS cover says
        // "November 2020"), so msMeta.session is only a fallback for papers whose
        // QP cover the OCR lost entirely (e.g. 4CH1 November 2021 — its MS cover
        // prints "November 2021", which is both session and publication month).
        String session = qpMeta != null && qpMeta.session() != null
                ? qpMeta.session() : msMeta == null ? null : msMeta.session();
        String paperReference = qpMeta != null && qpMeta.paperReference() != null
                ? qpMeta.paperReference() : msMeta == null ? null : msMeta.paperReference();

        PastPaperDraftDto.PaperMeta paper = new PastPaperDraftDto.PaperMeta(
                msMeta == null ? null : msMeta.board(),
                msMeta == null ? null : msMeta.qualification(),
                null,                                    // subject: never inferred
                null,                                    // unit: not extracted
                session,
                paperReference,
                qpMeta == null ? null : qpMeta.canonicalDocumentId(),
                msMeta == null ? null : msMeta.canonicalDocumentId());

        List<PastPaperDraftDto.QuestionDraft> questions = new ArrayList<>();
        for (QuestionDraft q : qpDraft.questions()) {
            List<PastPaperDraftDto.PartDraft> parts = new ArrayList<>();
            for (GlmOcrPaperDraftDto.PartDraft p : q.parts()) {
                parts.add(new PastPaperDraftDto.PartDraft(
                        p.label(),
                        p.text() == null ? "" : p.text(),
                        null,                             // command word: not guessed
                        p.marks() == null ? 0 : p.marks(), // null = unknown, not zero-credit
                        p.confidence()));
            }
            questions.add(new PastPaperDraftDto.QuestionDraft(
                    q.questionId(),
                    Integer.toString(q.number()),
                    q.stem() == null ? "" : q.stem(),
                    null,                                 // command word: not guessed
                    q.marks(),
                    null,                                 // T-011 ingests STRUCTURED
                    1,                                    // GLM-OCR Markdown exports are single-page
                    q.confidence(),
                    parts));
        }

        List<PastPaperDraftDto.MarkPointDraft> points = new ArrayList<>();
        for (MarkSchemeEntry entry : msDraft.entries()) {
            String ref = markPointRef(entry);
            if (entry.markPoints().isEmpty()) {
                // no "(N)" markers in the cell: the whole entry is the marking statement
                // (MCQ rationale rows, unsplit cells). One point keeps the evidence.
                points.add(new PastPaperDraftDto.MarkPointDraft(
                        ref, 0,
                        entry.answerText() == null ? "" : entry.answerText(),
                        entry.marks() == null ? 0 : entry.marks(),
                        List.of(),                         // acceptance criteria are teacher-authored
                        entry.confidence()));
            } else {
                for (MarkPoint mp : entry.markPoints()) {
                    points.add(new PastPaperDraftDto.MarkPointDraft(
                            ref, mp.ordinal() - 1,
                            mp.text() == null ? "" : mp.text(),
                            mp.marks() == null ? 0 : mp.marks(),
                            List.of(),
                            entry.confidence()));
                }
            }
        }

        PastPaperDraftDto.MarkSchemeDraft scheme = new PastPaperDraftDto.MarkSchemeDraft(
                "1",
                msMeta == null ? null : msMeta.canonicalDocumentId(),
                points);

        return new PastPaperDraftDto(
                PastPaperDraftDto.SUPPORTED_SCHEMA,
                paper,
                questions,
                scheme,
                BRIDGE_METHOD,
                qpDraft.reviewRequired() || msDraft.reviewRequired());
    }

    /**
     * Assembles the review-visible findings for the bridge record: the parser
     * reconciliation findings verbatim, the paper-total conflict (if any), and the
     * QP/MS draft warnings (defect evidence — e.g. October Q18's part-marks-sum vs
     * printed-total conflict). Parser warnings are relayed, never repaired.
     */
    public List<ReviewFinding> assembleReviewFindings(
            GlmOcrPaperDraftDto qpDraft, GlmOcrMarkSchemeDraftDto msDraft,
            GlmOcrReconciliationDto reconciliation) {
        List<ReviewFinding> findings = new ArrayList<>();
        for (Finding f : reconciliation.findings()) {
            findings.add(new ReviewFinding(
                    "RECONCILIATION", f.severity(), f.questionNumber(),
                    f.qpMarks(), f.msMarks(),
                    "Q" + nullSafe(f.questionNumber(), "?") + ": QP total "
                            + (f.qpMarks() == null ? "unknown" : f.qpMarks())
                            + " vs MS total "
                            + (f.msMarks() == null ? "unknown" : f.msMarks())
                            + " (" + f.severity() + ")"));
        }
        if (reconciliation.paperTotalConflict()) {
            findings.add(new ReviewFinding(
                    "RECONCILIATION", "paper-total-conflict", null,
                    reconciliation.qpPaperTotal(), reconciliation.msPaperTotal(),
                    "QP paper total " + reconciliation.qpPaperTotal()
                            + " vs MS paper total " + reconciliation.msPaperTotal()
                            + " — both preserved, never merged (evidence-first)"));
        }
        for (String warning : qpDraft.warnings()) {
            findings.add(new ReviewFinding(
                    "QP_WARNING", "warning", null, null, null, warning));
        }
        for (String warning : msDraft.warnings()) {
            findings.add(new ReviewFinding(
                    "MS_WARNING", "warning", null, null, null, warning));
        }
        findings.addAll(duplicatePartLabelFindings(qpDraft));
        return List.copyOf(findings);
    }

    /**
     * Parser fragmentation: the same part label appearing twice within one
     * question (a marks-bearing row plus an empty continuation). The persistence
     * layer keeps both rows and suffixes later labels deterministically
     * ({@code b-ii -> b-ii.2}); this finding tells the reviewer WHERE that
     * happened so they can merge or reject during review — content is never
     * silently dropped.
     */
    public List<ReviewFinding> duplicatePartLabelFindings(GlmOcrPaperDraftDto qpDraft) {
        List<ReviewFinding> findings = new ArrayList<>();
        if (qpDraft == null || qpDraft.questions() == null) {
            return findings;
        }
        for (QuestionDraft q : qpDraft.questions()) {
            if (q.parts() == null) {
                continue;
            }
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (GlmOcrPaperDraftDto.PartDraft p : q.parts()) {
                if (p.label() != null) {
                    counts.merge(p.label(), 1, Integer::sum);
                }
            }
            counts.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .forEach(e -> findings.add(new ReviewFinding(
                            "QP_WARNING", "duplicate-part-label",
                            Integer.toString(q.number()), null, null,
                            "Q" + q.number() + ": part label '" + e.getKey()
                                    + "' occurs " + e.getValue()
                                    + "x (parser fragmentation) — later rows relabelled '"
                                    + e.getKey() + ".2', '.3' … for review; merge or reject")));
        }
        return findings;
    }

    /**
     * MS entry label → T-011 mark-point ref. "11" → "11" (question-level);
     * "13(a)" → "13-a"; "13(b)(i)" → "13-b-i" (the QP part-label convention).
     */
    static String markPointRef(MarkSchemeEntry entry) {
        String printed = entry.label() == null ? Integer.toString(entry.number()) : entry.label();
        // strip a leading QWC asterisk: "*14" is Q14 with quality-of-communication marking
        String noStar = printed.startsWith("*") ? printed.substring(1) : printed;
        int paren = noStar.indexOf('(');
        String number = Integer.toString(entry.number());
        if (paren < 0) {
            return noStar.isBlank() ? number : noStar;
        }
        String part = noStar.substring(paren).replaceAll("[()]", "");
        return number + "-" + normalizePart(part);
    }

    /** "a" → "a"; "bi" → "b-i" (letter + roman subpart, QP label convention). */
    static String normalizePart(String part) {
        if (part == null || part.isBlank()) {
            return "x";
        }
        Matcher m = LETTER_THEN_ROMAN.matcher(part);
        if (m.matches()) {
            return m.group(1) + "-" + m.group(2);
        }
        return part;
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * One review-visible finding: parser reconciliation evidence or draft warning.
     * Stored as JSONB in the bridge record — the evidence-first trail.
     *
     * @param source        RECONCILIATION | QP_WARNING | MS_WARNING
     * @param severity      parser severity ("mismatch", "ms-only", …, "warning",
     *                      "paper-total-conflict")
     * @param questionNumber question the finding is about (null = paper-level)
     * @param qpMarks       QP-side evidence (marks or, for paper-total, the total)
     * @param msMarks       MS-side evidence
     * @param detail        human-readable evidence line, verbatim semantics
     */
    public record ReviewFinding(String source, String severity, String questionNumber,
                                Integer qpMarks, Integer msMarks, String detail) {
    }
}
