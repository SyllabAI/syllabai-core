package com.syllabai.diagnostic;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.SkillState;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explainable rule engine for T-027; unsupported categories are never guessed.
 *
 * <p>Listener order matters: {@code LearnerModelService} (@Order(10)) applies the
 * BKT/BDT/fluency updates for the attempt first; this listener (@Order(100)) then
 * reads the <em>post-update</em> learner state, so diagnosis never trails the
 * evidence by one attempt.</p>
 *
 * <p>Duplicate handling: a new inference for the same (learner, topic, type)
 * <em>supersedes</em> the previous active one — the old row is kept (research
 * history is append-only) but marked {@code superseded_at} and excluded from
 * active reads. Later evidence therefore replaces stale conclusions without
 * corrupting history.</p>
 */
@Service
public class StruggleInferenceService {
    static final String MODEL_VERSION = "rules-v0.2";
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

    @EventListener @Order(100) @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        Instant now = event.occurredAt(); Instant expires = now.plus(EXPIRY);
        // fetched once per event: the rules below all observe the same post-update snapshot
        Map<UUID, SkillState> statesByNode = learnerModel.skillStates(event.learnerId()).stream()
                .collect(java.util.stream.Collectors.toMap(SkillState::nodeId, s -> s, (a, b) -> a));
        for (UUID topicId : event.topicNodeIds()) {
            inferPrerequisiteGap(event.learnerId(), topicId, statesByNode, now, expires);
            inferProceduralFluency(event.learnerId(), topicId, statesByNode, now, expires);
            if (event.selfDoubtFlag()) save(event.learnerId(), topicId, StruggleType.METACOGNITIVE, "5a_self_efficacy",
                    SELF_DOUBT_PROBABILITY, Map.of("signal", "self_doubt_flag", "attempt_id", event.attemptId().toString()), now, expires);
        }
    }

    private void inferPrerequisiteGap(UUID learnerId, UUID topicId, Map<UUID, SkillState> statesByNode,
                                      Instant now, Instant expires) {
        var prerequisites = knowledgeGraph.prerequisiteChain(topicId);
        if (prerequisites.isEmpty()) return;
        // NB: Map.entry rejects null values — filter BEFORE pairing (a prerequisite
        // with no skill state is "no evidence", never a crash)
        List<Map.Entry<PrerequisiteView, SkillState>> weak = new ArrayList<>();
        for (PrerequisiteView p : prerequisites) {
            SkillState state = statesByNode.get(p.id());
            if (state != null && state.mastery() < PREREQUISITE_THRESHOLD) {
                weak.add(Map.entry(p, state));
            }
        }
        if (weak.isEmpty()) return;
        double weakest = weak.stream().mapToDouble(e -> e.getValue().mastery()).min().orElse(0.0);
        double probability = clamp(0.55 + (PREREQUISITE_THRESHOLD - weakest), 0.55, 0.95);
        var evidence = new HashMap<String, Object>();
        evidence.put("signal", "weak_prerequisite_mastery");
        evidence.put("weakest_mastery", weakest);
        evidence.put("prerequisite_count", weak.size());
        evidence.put("prerequisite_codes", weak.stream().map(e -> e.getKey().code()).toList());
        save(learnerId, topicId, StruggleType.PREREQUISITE_GAP, "1a_weak_prerequisite_mastery", probability, evidence, now, expires);
    }

    private void inferProceduralFluency(UUID learnerId, UUID topicId, Map<UUID, SkillState> statesByNode,
                                        Instant now, Instant expires) {
        SkillState state = statesByNode.get(topicId);
        if (state == null || state.proceduralFluencyGap() == null || state.proceduralFluencyGap() < FLUENCY_GAP_THRESHOLD) return;
        double gap = state.proceduralFluencyGap();
        save(learnerId, topicId, StruggleType.EXAM_LITERACY, "3b_procedural_fluency", clamp(0.50 + gap, 0.50, 0.95),
                Map.of("signal", "procedural_fluency_gap", "gap", gap), now, expires);
    }

    private void save(UUID learnerId, UUID topicId, StruggleType type, String subtype, double probability,
                      Map<String, Object> evidence, Instant generatedAt, Instant expiresAt) {
        // supersede prior active inference of the same (learner, topic, type):
        // history is preserved (rows never deleted), reads stay fresh
        List<StruggleInference> prior = inferences
                .findByLearnerIdAndTopicNodeIdAndTypeAndExpiresAtAfterAndSupersededAtIsNull(
                        learnerId, topicId, type, generatedAt);
        for (StruggleInference previous : prior) {
            previous.supersede(generatedAt);
            inferences.save(previous);
        }
        StruggleInference inference = new StruggleInference(learnerId, topicId, type, subtype, probability, evidence, MODEL_VERSION, generatedAt, expiresAt);
        inferences.save(inference);
        events.publishEvent(new StruggleInferredEvent(inference.learnerId(), inference.topicNodeId(), inference.type(), inference.subtype(), inference.probability(), inference.supportingEvidence(), inference.modelVersion(), generatedAt));
    }

    static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
