package com.syllabai.tutor;

import com.syllabai.learner.LearnerModelService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Learner-aware context assembly plus the T-026 intervention-policy decision. */
@Component
public class LearnerContextAssembler implements ContextAssembler {

    private static final String ANONYMOUS =
            "Learner state: not available for this request (anonymous/preview).";
    private static final double ACTIVE_MISCONCEPTION_THRESHOLD = 0.5;

    private final LearnerModelService learnerModel;
    private final TutorPolicyService policy;

    /** Spring production constructor; the legacy constructor keeps T-024 unit tests focused. */
    @Autowired
    public LearnerContextAssembler(LearnerModelService learnerModel, TutorPolicyService policy) {
        this.learnerModel = learnerModel;
        this.policy = policy;
    }

    public LearnerContextAssembler(LearnerModelService learnerModel) {
        this.learnerModel = learnerModel;
        this.policy = null;
    }

    @Override
    public TutorContext assemble(KnowledgeRetriever.KnowledgeContext knowledge,
                                 List<EvidenceItem> evidence, UUID learnerId) {
        Set<UUID> relevantNodes = knowledge.topics().stream()
                .map(KnowledgeRetriever.KnowledgeContext.MatchedTopic::nodeId)
                .collect(Collectors.toSet());
        knowledge.prerequisites().forEach(p -> relevantNodes.add(p.nodeId()));

        String learnerBrief = learnerId == null ? ANONYMOUS
                : learnerBrief(learnerId, relevantNodes, knowledge);
        TutorPolicyService.InterventionPlan plan = policy == null
                ? new TutorPolicyService.InterventionPlan(
                        TutorPolicyService.InterventionType.EXPLANATION,
                        "policy not supplied",
                        List.of("Explain from the supplied evidence."))
                : policy.select(learnerId, knowledge.topics(), knowledge.misconceptions());
        return new TutorContext(learnerBrief, knowledgeBrief(knowledge), List.copyOf(evidence), plan);
    }

    private String learnerBrief(UUID learnerId, Set<UUID> relevantNodes,
                                KnowledgeRetriever.KnowledgeContext knowledge) {
        Map<UUID, Double> masteryByNode = learnerModel.skillStates(learnerId).stream()
                .filter(s -> relevantNodes.isEmpty() || relevantNodes.contains(s.nodeId()))
                .collect(Collectors.toMap(s -> s.nodeId(), s -> s.mastery(), (a, b) -> a));
        Map<UUID, Double> fluencyGaps = learnerModel.skillStates(learnerId).stream()
                .filter(s -> relevantNodes.contains(s.nodeId()) && s.proceduralFluencyGap() != null)
                .collect(Collectors.toMap(s -> s.nodeId(), s -> s.proceduralFluencyGap(), (a, b) -> a));
        Set<UUID> activeMisconceptions = learnerModel.misconceptionStates(learnerId).stream()
                .filter(m -> m.probability() >= ACTIVE_MISCONCEPTION_THRESHOLD)
                .map(m -> m.misconceptionNodeId()).collect(Collectors.toSet());
        List<KnowledgeRetriever.KnowledgeContext.MisconceptionSignal> relevantMisconceptions =
                knowledge.misconceptions().stream()
                        .filter(m -> activeMisconceptions.contains(m.nodeId())).toList();
        if (masteryByNode.isEmpty() && relevantMisconceptions.isEmpty() && fluencyGaps.isEmpty()) {
            return "Learner state: no prior evidence on the topics in this question.";
        }
        StringBuilder sb = new StringBuilder("Learner state for this question:\n");
        knowledge.topics().forEach(topic -> {
            Double mastery = masteryByNode.get(topic.nodeId());
            if (mastery != null) sb.append("- mastery of '").append(topic.title()).append("': ")
                    .append(String.format(Locale.ROOT, "%.2f", mastery)).append('\n');
        });
        fluencyGaps.forEach((nodeId, gap) -> sb.append("- timed/untimed fluency gap on a relevant topic: ")
                .append(String.format(Locale.ROOT, "%+.2f", gap))
                .append(" (positive = weaker under timed conditions)\n"));
        relevantMisconceptions.forEach(m -> sb.append("- active misconception: ").append(m.title()).append('\n'));
        if (masteryByNode.isEmpty()) sb.append("- no mastery estimates yet for the matched topics\n");
        return sb.toString().strip();
    }

    private String knowledgeBrief(KnowledgeRetriever.KnowledgeContext knowledge) {
        if (knowledge.isEmpty()) return "Curriculum context: no topics matched this question.";
        StringBuilder sb = new StringBuilder("Curriculum context:\n");
        knowledge.topics().forEach(topic -> sb.append("- topic ").append(topic.code()).append(": ")
                .append(topic.title()).append('\n'));
        if (!knowledge.prerequisites().isEmpty()) {
            sb.append("Prerequisites of the matched topics:\n");
            knowledge.prerequisites().stream().limit(8).forEach(p -> sb.append("- ").append(p.title())
                    .append(" (").append(p.depth()).append(p.depth() == 1 ? " hop" : " hops deep").append(")\n"));
        }
        if (!knowledge.misconceptions().isEmpty()) {
            sb.append("Known misconceptions attached to these topics:\n");
            knowledge.misconceptions().stream().limit(6).forEach(m -> sb.append("- ").append(m.title()).append('\n'));
        }
        return sb.toString().strip();
    }
}
