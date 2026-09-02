package com.syllabai.learner;

import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.bkt.BktEngine;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner model aggregate (Master Spec §11, §12): reacts to assessment evidence
 * events and updates BKT mastery and BDT misconception probabilities.
 *
 * <p>This is the Observer in the evidence contract — assessment publishes, this
 * service updates state, and the research module logs telemetry, all decoupled
 * (Master Spec §23).</p>
 */
@Service
public class LearnerModelService {

    private static final Logger log = LoggerFactory.getLogger(LearnerModelService.class);

    private final SkillStateRepository skillStates;
    private final MisconceptionStateRepository misconceptionStates;
    private final BktEngine bktEngine;
    private final BdtEngine bdtEngine;
    private final LearnerProperties properties;

    public LearnerModelService(SkillStateRepository skillStates,
                               MisconceptionStateRepository misconceptionStates,
                               BktEngine bktEngine,
                               BdtEngine bdtEngine,
                               LearnerProperties properties) {
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.bktEngine = bktEngine;
        this.bdtEngine = bdtEngine;
        this.properties = properties;
    }

    @EventListener
    @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        updateMastery(event);
        updateMisconceptions(event);
    }

    private void updateMastery(AssessmentEvidenceRecordedEvent event) {
        var bktParams = properties.bkt().toParams();
        Instant when = event.occurredAt();
        List<SkillState> toSave = new ArrayList<>();
        for (UUID node : event.topicNodeIds()) {
            SkillState state = skillStates
                    .findByLearnerIdAndNodeId(event.learnerId(), node)
                    .orElseGet(() -> new SkillState(event.learnerId(), node, bktParams.l0(), when));
            double updated = bktEngine.update(state.mastery(), event.correctness(), bktParams);
            state.recordAttempt(event.correctness(), updated, when);
            toSave.add(state);
        }
        skillStates.saveAll(toSave);
        log.debug("BKT updated for learner {} on {} node(s): correct={}",
                event.learnerId(), event.topicNodeIds().size(), event.correctness());
    }

    private void updateMisconceptions(AssessmentEvidenceRecordedEvent event) {
        var bdtParams = properties.bdt().toParams();
        Instant when = event.occurredAt();
        List<MisconceptionState> toSave = new ArrayList<>();
        for (UUID misconception : event.misconceptionIds()) {
            MisconceptionState state = misconceptionStates
                    .findByLearnerIdAndMisconceptionNodeId(event.learnerId(), misconception)
                    .orElseGet(() -> new MisconceptionState(
                            event.learnerId(), misconception, bdtParams.prior(), when));
            double updated = event.correctness()
                    ? bdtEngine.updateOnCorrect(state.probability(), bdtParams)
                    : bdtEngine.updateOnTaggedDistractor(state.probability(), bdtParams);
            state.update(updated, when);
            toSave.add(state);
        }
        if (!toSave.isEmpty()) {
            misconceptionStates.saveAll(toSave);
            log.debug("BDT updated for learner {} on {} misconception(s)",
                    event.learnerId(), toSave.size());
        }
    }

    @Transactional(readOnly = true)
    public List<SkillState> skillStates(UUID learnerId) {
        return skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learnerId);
    }

    @Transactional(readOnly = true)
    public List<MisconceptionState> misconceptionStates(UUID learnerId) {
        return misconceptionStates.findByLearnerIdOrderByProbabilityDesc(learnerId);
    }
}
