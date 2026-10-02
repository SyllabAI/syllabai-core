package com.syllabai.learner;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.bkt.BktEngine;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
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
    private final AttemptRepository attempts;
    private final BktEngine bktEngine;
    private final BdtEngine bdtEngine;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;
    private final ApplicationEventPublisher events;

    public LearnerModelService(SkillStateRepository skillStates,
                               MisconceptionStateRepository misconceptionStates,
                               AttemptRepository attempts,
                               BktEngine bktEngine,
                               BdtEngine bdtEngine,
                               EbbinghausDecayService decayService,
                               LearnerProperties properties,
                               ApplicationEventPublisher events) {
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.attempts = attempts;
        this.bktEngine = bktEngine;
        this.bdtEngine = bdtEngine;
        this.decayService = decayService;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Order 10: learner-state updates run BEFORE downstream diagnosis
     * ({@code StruggleInferenceService}, order 100), so diagnosis always reads
     * the post-update model, never evidence-trailing state.
     */
    @EventListener
    @Order(10)
    @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        updateMastery(event);
        updateMisconceptions(event);
        updateFluencyGaps(event);
    }

    private void updateMastery(AssessmentEvidenceRecordedEvent event) {
        // S2/ADR-033: guess is priced per question format (MCQ 1/optionCount,
        // SHORT_ANSWER / STRUCTURED lenient priors, untyped → paper default) —
        // the 0.25 paper constant was silently a four-option-MCQ emission model.
        var bktParams = properties.bkt().toParams(event.questionType(), event.optionCount());
        var decayParams = properties.decay().toParams();
        Instant when = event.occurredAt();
        // spec points (T-C18 mapping) ride the SAME evidence class as topics:
        // one marked attempt is one BKT update per node it honestly tests —
        // the point nodes the hub's KG paints get real skills, review
        // scheduling and decay exactly like the topic nodes always did.
        List<UUID> evidenceNodes = new ArrayList<>(event.topicNodeIds());
        for (UUID sp : event.specPointNodeIds()) {
            if (!evidenceNodes.contains(sp)) {
                evidenceNodes.add(sp);
            }
        }
        List<SkillState> toSave = new ArrayList<>();
        for (UUID node : evidenceNodes) {
            SkillState state = skillStates
                    .findByLearnerIdAndNodeId(event.learnerId(), node)
                    .orElseGet(() -> new SkillState(event.learnerId(), node, bktParams.l0(), when));
            // S2 challenge C1 (ADR-033): the update prior is the COMPUTED, never-persisted
            // pre-attempt forecast — the anchor decayed over the practice gap — not the raw
            // anchor. Updating from the un-decayed anchor erased the gap: correct after 180
            // days read identically to correct after 10 seconds, retention evidence never
            // entered the trajectory, and the write path's prior contradicted the decayed
            // forecast every read surface shows for the same learner-node instant.
            // Fully ADR-031-consistent: the decayed value is consumed here and never
            // written — the posterior becomes the new anchor; τ stays frozen on P₀
            // (EbbinghausDecayService bands on the value passed), and the decay floor
            // keeps the prior at or above l₀ (the learner has demonstrably been exposed).
            double stored = state.mastery();   // ADR-031 anchor P₀
            Instant lastPracticed = state.lastPracticedAt();
            double prior = lastPracticed == null
                    ? stored
                    : decayService.decayed(stored, lastPracticed, when, decayParams);
            double posterior = bktEngine.update(prior, event.correctness(), bktParams);
            state.recordAttempt(event.correctness(), posterior, when);
            toSave.add(state);
            events.publishEvent(new MasteryUpdatedEvent(
                    event.learnerId(), event.attemptId(), node,
                    stored, posterior, event.correctness(),
                    state.attempts(), state.correctCount(),
                    prior,
                    lastPracticed == null ? 0L
                            : Math.max(0, Duration.between(lastPracticed, when).toDays()),
                    event.questionType(), event.optionCount(),
                    when));
        }
        skillStates.saveAll(toSave);
        log.debug("BKT updated for learner {} on {} node(s) (topics + spec points): correct={}",
                event.learnerId(), evidenceNodes.size(), event.correctness());
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

    /**
     * Paper B §16 procedural fluency gap per affected node: untimed accuracy −
     * timed accuracy over <em>graded</em> attempts; null until both conditions
     * are observed. Derived metric — BKT mastery stays condition-agnostic.
     *
     * <p>Deliberately TOPIC-scoped even when the event carries spec points: the
     * fluency aggregate attributes attempts via the question's primary topic,
     * so per-point condition splits would always aggregate empty here. Point
     * skills carry mastery, decay and review scheduling; fluency stays a
     * topic-level read until an attempt-attributed per-point query exists.</p>
     */
    private void updateFluencyGaps(AssessmentEvidenceRecordedEvent event) {
        for (UUID node : event.topicNodeIds()) {
            skillStates.findByLearnerIdAndNodeId(event.learnerId(), node).ifPresent(state -> {
                Double timedAccuracy = null;
                Double untimedAccuracy = null;
                for (Object[] row : attempts.aggregateGradedCorrectnessByCondition(
                        event.learnerId(), node)) {
                    boolean timed = (Boolean) row[0];
                    long total = ((Number) row[1]).longValue();
                    long correct = ((Number) row[2]).longValue();
                    double accuracy = total == 0 ? 0.0 : correct / (double) total;
                    if (timed) {
                        timedAccuracy = accuracy;
                    } else {
                        untimedAccuracy = accuracy;
                    }
                }
                Double gap = (timedAccuracy == null || untimedAccuracy == null)
                        ? null : untimedAccuracy - timedAccuracy;
                state.setProceduralFluencyGap(gap);
                skillStates.save(state);
            });
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

    /**
     * Read model for adaptive/display surfaces: every row comes back with its
     * staleness-relaxed probability (MED-2, ADR-032) — {@code effective = prior +
     * (P_e − prior)·e^(−age/τ_s)} recomputed from the (P_e, lastEvidenceAt)
     * anchor and never persisted. Sorted by the <em>effective</em> probability
     * descending: a fresh 0.6 diagnosis must outrank a stale 0.9 one, which the
     * repository's raw-probability ordering cannot express. Surfaces that need
     * the anchored row itself use {@link #misconceptionStates(UUID)}.
     */
    @Transactional(readOnly = true)
    public List<MisconceptionReading> misconceptionReadings(UUID learnerId) {
        var bdt = properties.bdt();
        double prior = bdt.prior();
        java.time.Duration tau = java.time.Duration.ofDays(bdt.stalenessTauDays());
        java.time.Instant now = java.time.Instant.now();
        return misconceptionStates.findByLearnerIdOrderByProbabilityDesc(learnerId).stream()
                .map(m -> new MisconceptionReading(m,
                        bdtEngine.relaxedToPrior(m.probability(), prior, m.lastEvidenceAt(), now, tau)))
                .sorted(java.util.Comparator.comparingDouble(MisconceptionReading::effective).reversed())
                .toList();
    }
}
