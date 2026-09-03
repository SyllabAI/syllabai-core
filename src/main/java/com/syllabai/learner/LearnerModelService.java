package com.syllabai.learner;

import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.bkt.BktEngine;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>BDT evidence semantics (Paper B §3.4): a wrong answer on a distractor tagged with
 * a misconception <em>strengthens</em> that misconception's probability
 * ({@code updateOnTaggedDistractor}); a correct answer on an item whose distractors
 * monitor that misconception <em>weakens</em> it ({@code updateOnCorrect}). A wrong
 * answer on an untagged distractor is ambiguous evidence and updates nothing.</p>
 */
@Service
public class LearnerModelService {

    private static final Logger log = LoggerFactory.getLogger(LearnerModelService.class);

    private final SkillStateRepository skillStates;
    private final MisconceptionStateRepository misconceptionStates;
    private final BktEngine bktEngine;
    private final BdtEngine bdtEngine;
    private final LearnerProperties properties;
    private final ApplicationEventPublisher events;

    public LearnerModelService(SkillStateRepository skillStates,
                               MisconceptionStateRepository misconceptionStates,
                               BktEngine bktEngine,
                               BdtEngine bdtEngine,
                               LearnerProperties properties,
                               ApplicationEventPublisher events) {
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.bktEngine = bktEngine;
        this.bdtEngine = bdtEngine;
        this.properties = properties;
        this.events = events;
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
            double prior = state.mastery();
            double posterior = bktEngine.update(prior, event.correctness(), bktParams);
            state.recordAttempt(event.correctness(), posterior, when);
            toSave.add(state);
            events.publishEvent(new MasteryUpdatedEvent(
                    event.learnerId(), event.attemptId(), node,
                    prior, state.mastery(), event.correctness(),
                    state.attempts(), state.correctCount(), when));
        }
        skillStates.saveAll(toSave);
        log.debug("BKT updated for learner {} on {} node(s): correct={}",
                event.learnerId(), event.topicNodeIds().size(), event.correctness());
    }

    private void updateMisconceptions(AssessmentEvidenceRecordedEvent event) {
        var bdtParams = properties.bdt().toParams();
        Instant when = event.occurredAt();
        // correct → weaken every misconception this item monitors;
        // wrong   → strengthen only the misconception expressed via the chosen distractor.
        List<UUID> targets = event.correctness()
                ? event.observedMisconceptionIds()
                : event.misconceptionIds();
        List<MisconceptionState> toSave = new ArrayList<>();
        for (UUID misconception : targets) {
            MisconceptionState state = misconceptionStates
                    .findByLearnerIdAndMisconceptionNodeId(event.learnerId(), misconception)
                    .orElseGet(() -> new MisconceptionState(
                            event.learnerId(), misconception, bdtParams.prior(), when));
            double prior = state.probability();
            double posterior = event.correctness()
                    ? bdtEngine.updateOnCorrect(prior, bdtParams)
                    : bdtEngine.updateOnTaggedDistractor(prior, bdtParams);
            state.update(posterior, when);
            toSave.add(state);
            events.publishEvent(new MisconceptionUpdatedEvent(
                    event.learnerId(), event.attemptId(), misconception,
                    prior, posterior, !event.correctness(), when));
        }
        if (!toSave.isEmpty()) {
            misconceptionStates.saveAll(toSave);
            log.debug("BDT updated for learner {} on {} misconception(s): correct={}",
                    event.learnerId(), toSave.size(), event.correctness());
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
