package com.syllabai.learner;

import com.syllabai.assessment.AttemptRepository;
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
    private final LearnerProperties properties;
    private final ApplicationEventPublisher events;

    public LearnerModelService(SkillStateRepository skillStates,
                               MisconceptionStateRepository misconceptionStates,
                               AttemptRepository attempts,
                               BktEngine bktEngine,
                               BdtEngine bdtEngine,
                               LearnerProperties properties,
                               ApplicationEventPublisher events) {
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.attempts = attempts;
        this.bktEngine = bktEngine;
        this.bdtEngine = bdtEngine;
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
        var bkt = properties.bkt();
        // S2/ADR-033: the emission model is format-aware. The evidence event now
        // carries the question format and option count, so guessability is priced
        // per item — a marked structured answer is near-unguessable, a 4-option
        // MCQ keeps the paper's 0.25 base, a 5-option MCQ gets 0.2. Events from
        // publishers that never knew the format (null/blank type or an unknown
        // name) keep the paper-default behaviour exactly.
        var bktParams = effectiveParams(bkt, event);
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
        log.debug("BKT updated for learner {} on {} node(s) (topics + spec points): correct={}",
                event.learnerId(), evidenceNodes.size(), event.correctness());
    }

    /**
     * S2/ADR-033: resolve the BKT parameter set for one evidence event. Only the
     * guess base varies by format (the probability of a correct answer WITHOUT
     * knowing the skill): MCQ_SINGLE prices it at 1/optionCount — the paper's
     * 0.25 is exactly the four-option value — SHORT_ANSWER at the configured
     * lucky-match rate, STRUCTURED at the configured near-zero rate. Slip stays
     * global (mistakes happen in every format); l0 and the learning transition
     * are format-agnostic. Legacy events without a format keep the paper
     * default, so the resolution is a strict refinement, never a behaviour
     * change for untyped evidence.
     */
    private static com.syllabai.learner.bkt.BktParams effectiveParams(
            LearnerProperties.Bkt props, AssessmentEvidenceRecordedEvent event) {
        String type = event.questionType();
        if (type == null || type.isBlank()) {
            return props.toParams();
        }
        double guess = switch (type) {
            case "MCQ_SINGLE" -> event.optionCount() >= 2
                    ? 1.0 / event.optionCount()
                    : props.guess();          // anomalous option list — paper default
            case "SHORT_ANSWER" -> props.shortAnswerGuess();
            case "STRUCTURED" -> props.structuredGuess();
            default -> props.guess();          // unknown future format — paper default
        };
        return new com.syllabai.learner.bkt.BktParams(props.l0(), props.slip(), guess, props.learnRate());
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
