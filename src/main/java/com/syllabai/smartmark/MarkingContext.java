package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.QuestionPart;
import java.util.List;

/**
 * Everything the candidate generator needs to propose a mark allocation for one
 * answer (Master Spec §15: answer normalization + question/scheme decomposition are
 * upstream of the LLM; the generator only aligns evidence to mark points).
 *
 * @param answer       the learner's answer to mark
 * @param part         the question part being answered
 * @param scheme       the mark scheme of the question version (validated view)
 * @param points       the scheme points in scope for this part, ordered
 */
public record MarkingContext(Answer answer, QuestionPart part, MarkScheme scheme,
                             List<MarkPoint> points) {
}
