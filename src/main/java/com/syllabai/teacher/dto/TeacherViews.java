package com.syllabai.teacher.dto;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.identity.User;
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

    /**
     * One roster row: the teacher's class list (T-029). The pilot has no class
     * entity, so the roster IS the STUDENT cohort; identity projection only —
     * no learning data here, the marking queue and /state carry that.
     */
    public record LearnerRosterView(UUID id, String displayName, String email,
                                    Instant createdAt) {

        public static LearnerRosterView from(User user) {
            return new LearnerRosterView(user.id(), user.displayName(), user.email(),
                    user.createdAt());
        }
    }

    /** one queue item: an answer awaiting (or carrying provisional) marks */
    public record AnswerMarkingView(
            UUID answerId, UUID attemptId, UUID learnerId, String learnerDisplayName,
            UUID questionId, String questionExternalRef, String partLabel, String partPrompt,
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

    public static AnswerMarkingView answer(Answer a, String learnerDisplayName) {
        Attempt attempt = a.attempt();
        Question question = attempt.question();
        QuestionPart part = a.questionPart();
        return new AnswerMarkingView(
                a.id(), attempt.id(), attempt.learnerId(), learnerDisplayName,
                question.id(), question.externalRef(), part.label(), part.prompt(), part.marks(),
                a.answerText(), a.markingState().name(), a.marksAwarded(),
                null, null);
    }

    public static AnswerMarkingView answer(Answer a, String learnerDisplayName,
                                           SmartMarkResult smart, HumanMark human) {
        AnswerMarkingView base = answer(a, learnerDisplayName);
        return new AnswerMarkingView(
                base.answerId(), base.attemptId(), base.learnerId(), base.learnerDisplayName(),
                base.questionId(), base.questionExternalRef(), base.partLabel(), base.partPrompt(),
                base.partMarks(), base.answerText(), base.markingState(),
                base.marksAwarded(), SmartMarkView.from(smart), HumanMarkView.from(human));
    }
}
