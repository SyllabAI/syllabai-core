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
            HumanMarkView latestHumanMark,
            UUID examPaperId, String paperTitle) {
    }

    /**
     * Opt-in paged marking-queue envelope (G-5): returned by
     * {@code GET /teacher/marking/answers} ONLY when page/size params are
     * present — without them the endpoint keeps returning the plain
     * {@link AnswerMarkingView} list, byte-compatible with the existing web
     * client and the calibration harness. Counts are the database's, never
     * estimates.
     */
    public record AnswerMarkingPageView(List<AnswerMarkingView> items, int page, int size,
                                        long totalElements, int totalPages) {
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
        return answer(a, learnerDisplayName, null, null, null);
    }

    public static AnswerMarkingView answer(Answer a, String learnerDisplayName,
                                           SmartMarkResult smart, HumanMark human) {
        return answer(a, learnerDisplayName, smart, human, null);
    }

    /**
     * Full builder. Paper context (G-5): {@code examPaperId} is a Question
     * column available from the already-loaded entity graph; the title comes
     * from the caller's batched ExamPaper lookup. Both are nullable —
     * SME question-bank answers have no paper row, and that absence is
     * rendered honestly, never invented.
     */
    public static AnswerMarkingView answer(Answer a, String learnerDisplayName,
                                           SmartMarkResult smart, HumanMark human,
                                           String paperTitle) {
        Attempt attempt = a.attempt();
        Question question = attempt.question();
        QuestionPart part = a.questionPart();
        return new AnswerMarkingView(
                a.id(), attempt.id(), attempt.learnerId(), learnerDisplayName,
                question.id(), question.externalRef(), part.label(), part.prompt(), part.marks(),
                a.answerText(), a.markingState().name(), a.marksAwarded(),
                SmartMarkView.from(smart), HumanMarkView.from(human),
                question.examPaperId(), paperTitle);
    }
}
