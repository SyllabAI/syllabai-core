package com.syllabai.assessment;

import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p>V20 paper-level integrity gate: questions under a REJECTED or FLAGGED exam
 * paper never serve, even when their own versions are VALIDATED — a rejected or
 * flagged paper signals a systematic defect (wrong source, mis-placement, mass
 * extraction failure) that per-version validation cannot express. Paper-less
 * questions (SEED_DEMO orphans) are unaffected.</p>
 */
@Service
@Transactional(readOnly = true)
public class ServableQuestionService {

    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final ExamPaperRepository examPapers;
    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    public ServableQuestionService(QuestionRepository questions,
                                   QuestionVersionRepository questionVersions,
                                   ExamPaperRepository examPapers) {
        this.questions = questions;
        this.questionVersions = questionVersions;
        this.examPapers = examPapers;
    }

    /** servable questions mapped to a topic (primary or question_topics), difficulty-ordered */
    public List<StudentQuestionView> activeByTopic(UUID topicNodeId) {
        return filterBlockedPapers(questions.findActiveByTopic(topicNodeId)).stream()
                .map(this::project)
                .filter(Objects::nonNull)
                .toList();
    }

    /** all servable questions, difficulty-ordered */
    public List<StudentQuestionView> allActive() {
        return filterBlockedPapers(questions.findAllActive()).stream()
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
        return filterBlockedPapers(questions.findActiveWithin(nodeIds)).stream()
                .map(this::project)
                .filter(Objects::nonNull)
                .toList();
    }

    /** a single servable question, or empty when missing/unservable (never throws) */
    public Optional<StudentQuestionView> findById(UUID id) {
        return questions.findWithOptions(id)
                .filter(q -> !paperBlocksServing(Set.of(), q.examPaperId()))
                .map(this::project)
                .filter(Objects::nonNull);
    }

    /** whether a question may still be served to learners (e.g. before recommending a retry) */
    public boolean isServable(UUID questionId) {
        return findById(questionId).isPresent();
    }

    /** how many servable questions exist on a topic (0 ⇒ honest empty state upstream) */
    public int countServableByTopic(UUID topicNodeId) {
        return activeByTopic(topicNodeId).size();
    }

    /** V20: drop questions whose paper is REJECTED/FLAGGED (paper-level integrity gate) */
    private List<Question> filterBlockedPapers(List<Question> candidates) {
        Set<UUID> blocked = new HashSet<>(examPapers.findIdsBlockingServing());
        return candidates.stream()
                .filter(q -> !blocked.contains(q.examPaperId()))
                .toList();
    }

    /** V20: single-question fast path — consults the DB only when a paper exists */
    private boolean paperBlocksServing(Set<UUID> ignoredCache, UUID examPaperId) {
        if (examPaperId == null) {
            return false; // paper-less (SEED_DEMO orphans): per-question rule only
        }
        return examPapers.findIdsBlockingServing().contains(examPaperId);
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
