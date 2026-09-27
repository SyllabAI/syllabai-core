package com.syllabai.tutor;

import java.util.List;
import java.util.UUID;

/** Port: grounded context assembly plus the policy decision consumed by generation. */
public interface ContextAssembler {

    TutorContext assemble(KnowledgeRetriever.KnowledgeContext knowledge,
                          List<EvidenceItem> evidence, UUID learnerId);

    /**
     * Assembled context for one ask.
     *
     * @param learnerBrief     per-topic mastery/misconception state (semantic)
     * @param memoryBrief      cross-session experiences on the matched topics
     *                         (s140 episodic: earlier asks, practice outcomes,
     *                         review status) — null/blank = no block (fresh
     *                         learner, anonymous preview, or the CLA surface)
     * @param knowledgeBrief   curriculum context of the match
     * @param evidence         the grounded evidence pool
     * @param interventionPlan the deterministic tutor-policy decision
     */
    record TutorContext(String learnerBrief, String memoryBrief, String knowledgeBrief,
                        List<EvidenceItem> evidence,
                        TutorPolicyService.InterventionPlan interventionPlan) {

        /**
         * Backward-compatible 4-field construction (s139 shape): no episodic
         * memory block. The CLA pipeline composes its context this way — its
         * anchored single-turn asks stay memory-free by design.
         */
        public TutorContext(String learnerBrief, String knowledgeBrief,
                            List<EvidenceItem> evidence,
                            TutorPolicyService.InterventionPlan interventionPlan) {
            this(learnerBrief, null, knowledgeBrief, evidence, interventionPlan);
        }

        /**
         * Backward-compatible 3-field construction for focused unit tests and
         * ports that do not need policy; production learner assembly always
         * supplies a plan.
         */
        public TutorContext(String learnerBrief, String knowledgeBrief, List<EvidenceItem> evidence) {
            this(learnerBrief, null, knowledgeBrief, evidence,
                    new TutorPolicyService.InterventionPlan(
                            TutorPolicyService.InterventionType.EXPLANATION,
                            "policy not supplied",
                            List.of("Explain from the supplied evidence.")));
        }

        public TutorContext {
            evidence = List.copyOf(evidence);
        }
    }
}
