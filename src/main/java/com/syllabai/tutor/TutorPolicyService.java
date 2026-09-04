package com.syllabai.tutor;

import com.syllabai.diagnostic.StruggleInference;
import com.syllabai.diagnostic.StruggleInferenceRepository;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** T-026 diagnosis-aware tutor policy; selection is deterministic and evidence-backed. */
@Service
public class TutorPolicyService {
    static final String POLICY_VERSION = "rules-v0.1";
    private static final double ACTIVE_MISCONCEPTION_THRESHOLD = 0.50;
    private final StruggleInferenceRepository inferences;
    private final LearnerModelService learnerModel;
    private final ApplicationEventPublisher events;

    public TutorPolicyService(StruggleInferenceRepository inferences, LearnerModelService learnerModel,
                              ApplicationEventPublisher events) {
        this.inferences = inferences; this.learnerModel = learnerModel; this.events = events;
    }

    public InterventionPlan select(UUID learnerId, List<KnowledgeRetriever.KnowledgeContext.MatchedTopic> topics,
                                   List<KnowledgeRetriever.KnowledgeContext.MisconceptionSignal> misconceptions) {
        if (learnerId == null) return plan(InterventionType.EXPLANATION, "anonymous request",
                List.of("Explain the concept from the supplied sources."));
        Set<UUID> topicIds = topics.stream().map(KnowledgeRetriever.KnowledgeContext.MatchedTopic::nodeId).collect(Collectors.toSet());
        List<StruggleInference> active = inferences.findByLearnerIdAndExpiresAtAfterOrderByProbabilityDesc(learnerId, Instant.now()).stream()
                .filter(i -> topicIds.contains(i.topicNodeId())).toList();
        StruggleInference strongest = active.stream().findFirst().orElse(null);
        InterventionPlan selected;
        if (strongest != null && strongest.probability() >= 0.65) selected = planFor(strongest);
        else {
            boolean activeMisconception = learnerModel.misconceptionStates(learnerId).stream()
                    .anyMatch(m -> m.probability() >= ACTIVE_MISCONCEPTION_THRESHOLD
                            && misconceptions.stream().anyMatch(s -> s.nodeId().equals(m.misconceptionNodeId())));
            selected = activeMisconception
                    ? plan(InterventionType.MISCONCEPTION_REMEDIATION, "active BDT misconception on a matched topic",
                    List.of("Address the misconception explicitly.", "Contrast it with the correct idea using source evidence.", "Finish with a brief verification question."))
                    : plan(InterventionType.EXPLANATION, "no high-confidence diagnostic signal",
                    List.of("Explain the matched concept from the supplied sources.", "Finish with a brief understanding check."));
        }
        events.publishEvent(new TutorInterventionSelectedEvent(learnerId, topics.stream().map(KnowledgeRetriever.KnowledgeContext.MatchedTopic::nodeId).toList(), selected.type().name(), selected.rationale(), POLICY_VERSION, Instant.now()));
        return selected;
    }
    private InterventionPlan planFor(StruggleInference i) {
        return switch (i.type()) {
            case PREREQUISITE_GAP -> plan(InterventionType.PREREQUISITE_REVIEW, i.subtype(), List.of("Review the weakest prerequisite before the target concept.", "Connect it back to the learner's question.", "Check understanding before moving forward."));
            case EXAM_LITERACY -> plan(InterventionType.PROCEDURAL_FLUENCY, i.subtype(), List.of("Explain the method briefly.", "Give a small timed-style verification step.", "Encourage transfer to a nearby exam-style task."));
            case METACOGNITIVE -> plan(InterventionType.METACOGNITIVE_CHECK, i.subtype(), List.of("Use a low-pressure verification step.", "Ask the learner to state why their answer is justified.", "Avoid framing the learner as deficient."));
            default -> plan(InterventionType.EXPLANATION, "unsupported struggle category for v0 intervention rules", List.of("Use a normal source-grounded explanation."));
        };
    }
    private static InterventionPlan plan(InterventionType type, String rationale, List<String> actions) { return new InterventionPlan(type, rationale, actions); }
    public enum InterventionType { EXPLANATION, MISCONCEPTION_REMEDIATION, PREREQUISITE_REVIEW, PROCEDURAL_FLUENCY, METACOGNITIVE_CHECK }
    public record InterventionPlan(InterventionType type, String rationale, List<String> actions) { public InterventionPlan { actions = List.copyOf(actions); } }
}
