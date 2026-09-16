package com.syllabai.tutor;

import com.syllabai.curriculum.CurriculumScope;
import java.util.List;
import java.util.UUID;

/**
 * Port: KG-side retrieval for a tutor query (Master Spec §13, T-024). Knows
 * nothing about vectors or generation — implementations resolve which
 * curriculum topics a question is about and assemble the pedagogical context
 * (prerequisites, misconceptions) those topics imply. Intent resolution is
 * deterministic in v0: no LLM invents entities (Master Spec §7 — the pipeline
 * never guesses beyond what the KG actually contains).
 *
 * <p>T-C07: retrieval is always curriculum-scoped — the caller resolves the
 * active {@link CurriculumScope} once per request (fail-closed: unresolved
 * scope ⇒ the orchestrator refuses without calling this port) and passes it
 * to every retrieval call. There is deliberately no unscoped overload.</p>
 */
public interface KnowledgeRetriever {

    /**
     * @param query     the learner's question
     * @param maxTopics bound on matched topics
     * @param scope     the active curriculum scope (non-null; matching is
     *                  restricted to the scope's intent surface — nodes outside
     *                  it are invisible even when their titles would match)
     * @return matched topics + their prerequisite/misconception context
     */
    KnowledgeContext retrieve(String query, int maxTopics, CurriculumScope scope);

    /**
     * KG context for one query.
     *
     * @param topics          topics whose titles matched the query (ranked by specificity)
     * @param prerequisites   prerequisite chains of the matched topics (remediation paths)
     * @param misconceptions  misconceptions attached to the matched topics
     */
    record KnowledgeContext(List<MatchedTopic> topics,
                             List<PrerequisiteLink> prerequisites,
                             List<MisconceptionSignal> misconceptions) {

        public boolean isEmpty() {
            return (topics == null || topics.isEmpty())
                    && (misconceptions == null || misconceptions.isEmpty());
        }

        /**
         * @param nodeId        matched topic node id
         * @param code          KG code
         * @param title         topic title
         * @param matchScore    deterministic match specificity 0..1
         */
        public record MatchedTopic(UUID nodeId, String code, String title, double matchScore) {
        }

        /**
         * @param forTopicId   the matched topic that needs this prerequisite
         * @param nodeId       prerequisite node id
         * @param title        prerequisite title
         * @param depth        1 = direct prerequisite, deeper = transitive
         */
        public record PrerequisiteLink(UUID forTopicId, UUID nodeId, String title, int depth) {
        }

        /**
         * @param forTopicId  the matched topic this misconception attaches to
         * @param nodeId      misconception node id
         * @param title       misconception statement
         */
        public record MisconceptionSignal(UUID forTopicId, UUID nodeId, String title) {
        }
    }
}
