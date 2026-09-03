package com.syllabai.assessment;

/**
 * Whether a bank question may serve to learners (Specification pattern, Master
 * Spec §7/§22): active, and for STRUCTURED questions the current version must be
 * VALIDATED — ingested content never serves silently. MCQs keep their Wave-0 flat
 * serving rule (SEED_DEMO items are live-validated v1 backfills).
 */
public final class ServableQuestionSpec {

    public boolean isSatisfiedBy(Question question, QuestionVersion currentVersion) {
        if (!question.active()) {
            return false;
        }
        if (question.type() != Question.Type.STRUCTURED) {
            return true;
        }
        return currentVersion != null
                && currentVersion.validationState() == QuestionVersion.ValidationState.VALIDATED;
    }
}
