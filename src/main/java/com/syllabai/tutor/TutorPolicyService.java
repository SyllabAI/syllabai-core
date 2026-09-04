package com.syllabai.tutor;

import com.syllabai.diagnostic.StruggleInference;
import com.syllabai.diagnostic.StruggleInferenceRepository;
import com.syllabai.diagnostic.StruggleType;
import com.syllabai.learner.LearnerModelService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-026 diagnosis-aware tutor policy. Chooses an intervention style from
 * persisted diagnostic evidence plus the live learner model; it does not
 * generate content and it never invents a diagnosis.
 */
@Service
public class TutorPolicyService {

    static final double ACTIVE_MISCONCEPTION_THRESHOLD = 0.50;

    private final StruggleInferenceRepository inferences;
    private final LearnerModelService learnerModel;

    public TutorPolicyService(StruggleInferenceRepository inferences,
                              LearnerModelService learnerModel) {
        this.inferences = inferences;
        this.learnerModel = learnerModel;
    }

    @Transactional(readOnly = true)
    public InterventionPlan select(UUID learnerId,
                                   List<KnowledgeRetriever.KnowledgeContext.MatchedTopic> topics,
                                   List<KnowledgeRetriever.KnowledgeContext.MisconceptionSignal> misconceptions) {
        if (learnerId == null) {
            return new InterventionPlan(InterventionType.EXPLANATION,
                    "anonymous request", List.of("Explain the concept from the supplied sources."));
        }

        Set<UUID> topicIds = topics.stream()
                .map(KnowledgeRetriever.KnowledgeContext.MatchedTopic::nodeId)
                .collect(Collectors.toSet());
        List<StruggleInference> active = inferences.findByLearnerIdAndExpiresAtAfterOrderByProbabilityDesc(
                learnerId, Instant.now()).stream()
                .filter(i -> topicIds.contains(i.topicNodeId()))
                .toList();

        StruggleInference strongest = active.stream().findFirst().orElse(null);
        if (strongest != null && strongest.probability() >= 0.65) {
            return planFor(strongest);
        }

        boolean activeMisconception = learnerModel.misconceptionStates(learnerId).stream()
                .anyMatch(m -> m.probability() >= ACTIVE_MISCONCEPTION_THRESHOLD
                        && misconceptions.stream().anyMatch(signal -> signal.nodeId().equals(m.misconceptionNodeId())));
        if (activeMisconception) {
            return new InterventionPlan(InterventionType.MISCONCEPTION_REMEDIATION,
                    "active BDT misconception on a matched topic",
                    List.of("Address the misconception explicitly.",
                            "Use the source evidence to contrast the misconception with the correct idea.",
                            "Finish with a brief verification question."));
        }

        return new InterventionPlan(InterventionType.EXPLANATION,
                "no high-confidence diagnostic signal",
                List.of("Explain the matched concept from the supplied sources.",
                        "Finish with a brief understanding check."));
    }

    private InterventionPlan planFor(StruggleInference inference) {
        return switch (inference.type()) {
            case PREREQUISITE_GAP -> new InterventionPlan(
                    InterventionType.PREREQUISITE_REVIEW,
                    inference.subtype(),
                    List.of("Review the weakest prerequisite before the target concept.",
                            "Connect the prerequisite back to the learner's question.",
                            "Check understanding before moving forward."));
            case EXAM_LITERACY -> new InterventionPlan(
                    InterventionType.PROCEDURAL_FLUENCY,
                    inference.subtype(),
                    List.of("Explain the method briefly.",
                            "Give a small timed-style verification step.",
                            "Encourage transfer to a nearby exam-style task."));
            case METACOGNITIVE -> new InterventionPlan(
                    InterventionType.METACOGNITIVE_CHECK,
                    inference.subtype(),
                    List.of("Use a low-pressure verification step.",
                            "Ask the learner to state why their answer is justified.",
                            "Avoid framing the learner as deficient."));
            default -> new InterventionPlan(InterventionType.EXPLANATION,
                    "unsupported struggle category for v0 intervention rules",
                    List.of("Use a normal source-grounded explanation."));
        };
    }

    public enum InterventionType {
        EXPLANATION,
        MISCONCEPTION_REMEDIATION,
        PREREQUISITE_REVIEW,
        PROCEDURAL_FLUENCY,
        METACOGNITIVE_CHECK
    }

    public record InterventionPlan(InterventionType type, String rationale, List<String> actions) {
        public InterventionPlan {
            actions = List.copyOf(actions);
        }
    }
}
