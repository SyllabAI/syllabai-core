package com.syllabai.teacher.dto;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.SmartMarkResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Teacher-facing marking projections (Master Spec §22 DTO boundaries).
 */
public final class TeacherViews {

    private TeacherViews() {
    }

    /** one queue item: an answer awaiting (or carrying provisional) marks */
    public record AnswerMarkingView(
            UUID answerId, UUID attemptId, UUID learnerId, UUID questionId,
            String questionExternalRef, String partLabel, String partPrompt,
            int partMarks, String answerText, String markingState,
            Integer marksAwarded, SmartMarkView latestSmartMark,
            HumanMarkView latestHumanMark) {
    }

    public record SmartMarkView(UUID id, String pipelineVersion, String modelId,
                                int marksAwarded, Double confidence,
                                boolean validationPassed, String failureReason,
                                List<Map<String, Object>> breakdown, Instant createdAt) {

        public static SmartMarkView from(SmartMarkResult r) {
            return r == null ? null : new SmartMarkView(
                    r.id(), r.pipelineVersion(), r.modelId(), r.marksAwarded(),
                    r.confidence(), r.validationPassed(), r.failureReason(),
                    r.breakdown(), r.createdAt());
        }
    }

    public record HumanMarkView(UUID id, UUID markerId, int marksAwarded,
                                Map<String, Integer> perPointDecisions,
                                String comments, Instant createdAt) {

        public static HumanMarkView from(HumanMark h) {
            return h == null ? null : new HumanMarkView(
                    h.id(), h.markerId(), h.marksAwarded(), h.perPointDecisions(),
                    h.comments(), h.createdAt());
        }
    }

    public static AnswerMarkingView answer(Answer a) {
        Attempt attempt = a.attempt();
        Question question = attempt.question();
        QuestionPart part = a.questionPart();
        return new AnswerMarkingView(
                a.id(), attempt.id(), attempt.learnerId(), question.id(),
                question.externalRef(), part.label(), part.prompt(), part.marks(),
                a.answerText(), a.markingState().name(), a.marksAwarded(),
                null, null);
    }

    public static AnswerMarkingView answer(Answer a, SmartMarkResult smart, HumanMark human) {
        AnswerMarkingView base = answer(a);
        return new AnswerMarkingView(
                base.answerId(), base.attemptId(), base.learnerId(), base.questionId(),
                base.questionExternalRef(), base.partLabel(), base.partPrompt(),
                base.partMarks(), base.answerText(), base.markingState(),
                base.marksAwarded(), SmartMarkView.from(smart), HumanMarkView.from(human));
    }
}
