package com.syllabai.assessment;

import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner-facing servable-question read model (Master Spec §7/§22): the ONE
 * owner of the boundary rule "active + (MCQ) or (STRUCTURED with a VALIDATED
 * current version) — ingested/unvalidated content never serves". Extracted from
 * {@code QuestionController} so read models outside the assessment module
 * (T-033 next-best-action counting, retry targeting) reuse the exact same rule
 * instead of mirroring it and drifting.
 */
@Service
@Transactional(readOnly = true)
public class ServableQuestionService {

    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    public ServableQuestionService(QuestionRepository questions,
                                   QuestionVersionRepository questionVersions) {
        this.questions = questions;
        this.questionVersions = questionVersions;
    }

    /** servable questions mapped to a topic (primary or question_topics), difficulty-ordered */
    public List<StudentQuestionView> activeByTopic(UUID topicNodeId) {
        return questions.findActiveByTopic(topicNodeId).stream()
                .map(this::project)
                .filter(Objects::nonNull)
                .toList();
    }

    /** all servable questions, difficulty-ordered */
    public List<StudentQuestionView> allActive() {
        return questions.findAllActive().stream()
                .map(this::project)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * All servable questions whose topic mapping falls inside the given node
     * set (a subject's PART_OF subtree) — the subject-scoped practice surface
     * (pilot-readiness session-56: the unscoped list served another subject's
     * questions under the 4CH1 surface, landing evidence the learner's
     * subject-scoped panels could not see). Same servability boundary, same
     * difficulty ordering, only the scope narrows.
     */
    public List<StudentQuestionView> activeWithin(java.util.Collection<UUID> nodeIds) {
        return questions.findActiveWithin(nodeIds).stream()
                .map(this::project)
                .filter(Objects::nonNull)
                .toList();
    }

    /** a single servable question, or empty when missing/unservable (never throws) */
    public Optional<StudentQuestionView> findById(UUID id) {
        return questions.findWithOptions(id).map(this::project).filter(Objects::nonNull);
    }

    /** whether a question may still be served to learners (e.g. before recommending a retry) */
    public boolean isServable(UUID questionId) {
        return findById(questionId).isPresent();
    }

    /** how many servable questions exist on a topic (0 ⇒ honest empty state upstream) */
    public int countServableByTopic(UUID topicNodeId) {
        return activeByTopic(topicNodeId).size();
    }

    /** null when the spec rejects the question (unvalidated content never serves) */
    private StudentQuestionView project(Question question) {
        if (question.type() != Question.Type.STRUCTURED) {
            return servable.isSatisfiedBy(question, null)
                    ? StudentQuestionView.from(question) : null;
        }
        return questionVersions.findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .filter(version -> servable.isSatisfiedBy(question, version))
                .map(version -> StudentQuestionView.structured(question, version))
                .orElse(null);
    }
}
