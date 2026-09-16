package com.syllabai.intervention;

import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.shared.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The E2 first scenario (INTERVENTION_RUN_PROTOTYPE.md §11): a deterministic,
 * recommendation-backed creation of one bounded InterventionRun.
 *
 * <p>Composition only — no new semantics are invented here:</p>
 * <ul>
 *   <li>the diagnosis snapshot IS the existing deterministic NBA output
 *       (policy {@code nba-rules/*}, reason code, reason detail) — referenced,
 *       never recomputed or reinterpreted (contract §7 evidence-snapshot
 *       invariant);</li>
 *   <li>the intervention definition is the fixed prototype practice
 *       intervention ({@link #INTERVENTION_VERSION}) with its bounded
 *       application-defined tool list (contract §8) — the stable hash is
 *       derived by {@link InterventionRunService};</li>
 *   <li>the learner-state snapshot is a REFERENCE to the observed skill-state
 *       row (node id + attempts + update instant), not a copy; an unmeasured
 *       topic is pinned honestly as {@code unmeasured} (never zero);</li>
 *   <li>the run records execution identity only: learner-state mutation stays
 *       exclusively in the governed learner-model path (contract §9).</li>
 * </ul>
 *
 * <p>Fail-closed: an unknown subject root is a 404; when no
 * {@link ActionType#PRACTISE_QUESTIONS} action exists for the learner there is
 * no intervention to run — a 404, never a fabricated or empty run.</p>
 */
@Service
public class InterventionRunScenarioService {

    /** The prototype intervention definition (contract §4.1 intervention block). */
    public static final String INTERVENTION_VERSION = "practice-intervention/v1";

    /**
     * Bounded tool list for one practice intervention (contract §8 —
     * application-defined, LLMs never expand it).
     */
    public static final String ALLOWED_TOOLS_JSON =
            "[\"get_specification_context\",\"get_learner_state\",\"start_practice\"]";

    static final String ORIGIN = "NBA";

    private final NextBestActionService nextBestActions;
    private final SubjectRepository subjects;
    private final SkillStateRepository skillStates;
    private final InterventionRunService runs;

    public InterventionRunScenarioService(NextBestActionService nextBestActions,
                                          SubjectRepository subjects,
                                          SkillStateRepository skillStates,
                                          InterventionRunService runs) {
        this.nextBestActions = nextBestActions;
        this.subjects = subjects;
        this.skillStates = skillStates;
        this.runs = runs;
    }

    /**
     * Creates one practice InterventionRun from the learner's current
     * deterministic next-best-action (the contract's first scenario).
     */
    @Transactional
    public InterventionRun createFromRecommendation(UUID learnerId, UUID rootId) {
        Subject subject = subjects.findByKnowledgeNodeId(rootId)
                .orElseThrow(() -> new NotFoundException("subject for knowledge root", rootId));

        NextBestActionsView view = nextBestActions.actionsFor(learnerId, rootId);
        NextBestActionsView.NextBestActionView action = view.actions().stream()
                .filter(a -> a.actionType() == ActionType.PRACTISE_QUESTIONS)
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "practice recommendation for learner " + learnerId + " under root", rootId));

        // learner-state snapshot REFERENCE: what the recommendation observed at
        // creation time (attempts + last update pin the row; unmeasured topics
        // are pinned honestly, never as zeros)
        String learnerStateRef = skillStates
                .findByLearnerIdAndNodeId(learnerId, action.targetNodeId())
                .map(state -> "skill-state:%s:a%d:u%d".formatted(
                        action.targetNodeId(), state.attempts(),
                        state.updatedAt() == null ? 0L : state.updatedAt().toEpochMilli()))
                .orElse("skill-state:" + action.targetNodeId() + ":unmeasured");

        String diagnosisRef = "nba:%s:%s:%s:rank%d:%s".formatted(
                view.policy(), rootId, action.targetNodeId(),
                action.rank(), action.reasonCode());

        return runs.create(new InterventionRunService.CreateCommand(
                learnerId,
                subject.id(),
                subject.curriculumVersion().id(),
                ORIGIN,
                "[\"" + action.targetNodeId() + "\"]",
                "[]",
                "[]",
                diagnosisRef,
                learnerStateRef,
                view.policy(),
                action.actionType().name(),
                INTERVENTION_VERSION,
                null,
                ALLOWED_TOOLS_JSON));
    }
}
