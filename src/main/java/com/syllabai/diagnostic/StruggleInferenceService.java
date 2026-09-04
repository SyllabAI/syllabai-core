package com.syllabai.diagnostic;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.SkillState;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explainable rule engine for T-027; unsupported categories are never guessed. */
@Service
public class StruggleInferenceService {
    static final String MODEL_VERSION = "rules-v0.1";
    static final double PREREQUISITE_THRESHOLD = 0.50;
    static final double FLUENCY_GAP_THRESHOLD = 0.15;
    static final double SELF_DOUBT_PROBABILITY = 0.80;
    static final Duration EXPIRY = Duration.ofDays(7);
    private final StruggleInferenceRepository inferences;
    private final LearnerModelService learnerModel;
    private final KnowledgeGraphService knowledgeGraph;
    private final ApplicationEventPublisher events;

    public StruggleInferenceService(StruggleInferenceRepository inferences, LearnerModelService learnerModel,
                                    KnowledgeGraphService knowledgeGraph, ApplicationEventPublisher events) {
        this.inferences = inferences; this.learnerModel = learnerModel; this.knowledgeGraph = knowledgeGraph; this.events = events;
    }
    @EventListener @Order(20) @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        Instant now = event.occurredAt(); Instant expires = now.plus(EXPIRY);
        for (UUID topicId : event.topicNodeIds()) {
            inferPrerequisiteGap(event.learnerId(), topicId, now, expires);
            inferProceduralFluency(event.learnerId(), topicId, now, expires);
            if (event.selfDoubtFlag()) save(event.learnerId(), topicId, StruggleType.METACOGNITIVE, "5a_self_efficacy",
                    SELF_DOUBT_PROBABILITY, Map.of("signal", "self_doubt_flag", "attempt_id", event.attemptId().toString()), now, expires);
        }
    }
    private void inferPrerequisiteGap(UUID learnerId, UUID topicId, Instant now, Instant expires) {
        var prerequisites = knowledgeGraph.prerequisiteChain(topicId);
        if (prerequisites.isEmpty()) return;
        Map<UUID, SkillState> states = learnerModel.skillStates(learnerId).stream()
                .collect(java.util.stream.Collectors.toMap(SkillState::nodeId, s -> s, (a,b) -> a));
        var weak = prerequisites.stream().map(p -> Map.entry(p, states.get(p.id())))
                .filter(e -> e.getValue() != null && e.getValue().mastery() < PREREQUISITE_THRESHOLD).toList();
        if (weak.isEmpty()) return;
        double weakest = weak.stream().mapToDouble(e -> e.getValue().mastery()).min().orElse(0.0);
        double probability = clamp(0.55 + (PREREQUISITE_THRESHOLD - weakest), 0.55, 0.95);
        var evidence = new HashMap<String,Object>(); evidence.put("signal", "weak_prerequisite_mastery");
        evidence.put("weakest_mastery", weakest); evidence.put("prerequisite_count", weak.size());
        evidence.put("prerequisite_codes", weak.stream().map(e -> e.getKey().code()).toList());
        save(learnerId, topicId, StruggleType.PREREQUISITE_GAP, "1a_weak_prerequisite_mastery", probability, evidence, now, expires);
    }
    private void inferProceduralFluency(UUID learnerId, UUID topicId, Instant now, Instant expires) {
        SkillState state = learnerModel.skillStates(learnerId).stream().filter(s -> topicId.equals(s.nodeId())).findFirst().orElse(null);
        if (state == null || state.proceduralFluencyGap() == null || state.proceduralFluencyGap() < FLUENCY_GAP_THRESHOLD) return;
        double gap = state.proceduralFluencyGap();
        save(learnerId, topicId, StruggleType.EXAM_LITERACY, "3b_procedural_fluency", clamp(0.50 + gap, 0.50, 0.95),
                Map.of("signal", "procedural_fluency_gap", "gap", gap), now, expires);
    }
    private void save(UUID learnerId, UUID topicId, StruggleType type, String subtype, double probability,
                      Map<String,Object> evidence, Instant generatedAt, Instant expiresAt) {
        StruggleInference saved = inferences.save(new StruggleInference(learnerId, topicId, type, subtype, probability, evidence, MODEL_VERSION, generatedAt, expiresAt));
        events.publishEvent(new StruggleInferredEvent(saved.learnerId(), saved.topicNodeId(), saved.type(), saved.subtype(), saved.probability(), saved.supportingEvidence(), saved.modelVersion(), generatedAt));
    }
    static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
