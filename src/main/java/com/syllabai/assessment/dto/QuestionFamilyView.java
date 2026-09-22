package com.syllabai.assessment.dto;

import java.util.List;

/**
 * One WHOLE SME question as the learner meets it in a Save-My-Exams set page —
 * the demo's serving unit (syllabai-demo {@code build_bundles.py}: one card per
 * SME question, stimulus + every part together, SME page order).
 *
 * <p>The corpus import (sme-corpus-import-v1, ADR-026) stored each SME question
 * as one row per MCQ part ({@code -pN} suffix) plus, for mixed questions, one
 * row for the structured section ({@code -s} suffix). A row-scoped list
 * therefore serves a part without the stimulus it depends on — the
 * "substances P/Q/R/S table" defect (session-120: States of matter served
 * {@code sme-eq-1-6-ionic-bonding-q1-p4} alone). This DTO reassembles the whole
 * question server-side so no consumer can ever see a part orphaned from its
 * family again.</p>
 *
 * <p>Fields mirror the session-120 client module {@code exam-families.ts}
 * (verified against production: family members share topic tags and difficulty;
 * {@code qN} in the ref equals the SME question's 0-based page order):</p>
 * <ul>
 *   <li>{@code key} — the stable identity for scroll/save/bookmark: the family
 *       base ref ({@code sme-eq-…-qN}) for corpus rows, the row id for anything
 *       outside the SME ref convention</li>
 *   <li>{@code parts} — the member rows in SME part order (the first carries the
 *       shared stimulus); each member is a full {@link StudentQuestionView}
 *       because attempts, options and structured sub-parts are per row</li>
 *   <li>{@code marks} — the SME question's total (sum over members)</li>
 *   <li>{@code type} — {@code "MCQ"} when every member is a non-STRUCTURED row,
 *       else {@code "STRUCTURED"} (a mixed MCQ+structured question is a
 *       structured question, like the demo's difficulty tabs)</li>
 *   <li>{@code multi} — true when the SME question was stored as several rows</li>
 * </ul>
 */
public record QuestionFamilyView(
        String key, String ref, int marks, int difficulty, String type,
        boolean multi, List<StudentQuestionView> parts) {
}
