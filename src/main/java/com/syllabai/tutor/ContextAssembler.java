package com.syllabai.tutor;

import java.util.List;
import java.util.UUID;

/** Port: grounded context assembly plus the policy decision consumed by generation. */
public interface ContextAssembler {

    TutorContext assemble(KnowledgeRetriever.KnowledgeContext knowledge,
                          List<EvidenceItem> evidence, UUID learnerId);

    /**
     * Backward-compatible 3-field construction for focused unit tests and ports
     * that do not need policy; production learner assembly always supplies a plan.
     */
    record TutorContext(String learnerBrief, String knowledgeBrief,
                        List<EvidenceItem> evidence,
                        TutorPolicyService.InterventionPlan interventionPlan) {
        public TutorContext(String learnerBrief, String knowledgeBrief, List<EvidenceItem> evidence) {
            this(learnerBrief, knowledgeBrief, evidence,
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
