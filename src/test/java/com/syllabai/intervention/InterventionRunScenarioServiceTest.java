package com.syllabai.intervention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E2 scenario composition tests: the run is created FROM the deterministic
 * NBA output with honest snapshot references — no recomputation, no invented
 * semantics, fail-closed when there is nothing to intervene on.
 */
@ExtendWith(MockitoExtension.class)
class InterventionRunScenarioServiceTest {

    @Mock NextBestActionService nextBestActions;
    @Mock SubjectRepository subjects;
    @Mock SkillStateRepository skillStates;
    @Mock InterventionRunService runs;

    private InterventionRunScenarioService scenario;

    private final UUID learnerId = UUID.randomUUID();
    private final UUID rootId = UUID.randomUUID();
    private final UUID topicId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID curriculumVersionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scenario = new InterventionRunScenarioService(nextBestActions, subjects, skillStates, runs);
    }

    @Test
    void createsRunFromThePracticeRecommendationWithHonestSnapshotReferences() {
        Subject subject = mock(Subject.class);
        CurriculumVersion version = mock(CurriculumVersion.class);
        when(subjects.findByKnowledgeNodeId(rootId)).thenReturn(Optional.of(subject));
        when(subject.id()).thenReturn(subjectId);
        when(subject.curriculumVersion()).thenReturn(version);
        when(version.id()).thenReturn(curriculumVersionId);
        when(nextBestActions.actionsFor(learnerId, rootId)).thenReturn(view(
                new NextBestActionsView.NextBestActionView(0, ActionType.PRACTISE_QUESTIONS,
                        ReasonCode.LOW_MASTERY, topicId, "4CH1-S1-a", "States of matter",
                        null, 3, "mastery 0.18 below the 0.45 floor")));
        SkillState state = mock(SkillState.class);
        when(skillStates.findByLearnerIdAndNodeId(learnerId, topicId))
                .thenReturn(Optional.of(state));
        when(state.attempts()).thenReturn(2);
        when(state.updatedAt()).thenReturn(Instant.ofEpochMilli(1_700_000_000_000L));
        when(runs.create(any())).thenAnswer(invocation -> {
            InterventionRunService.CreateCommand c = invocation.getArgument(0);
            return new InterventionRun(c.learnerId(), c.subjectId(), c.curriculumVersionId(),
                    c.origin(), c.targetSpecificationPoints(), c.questionPartIds(),
                    c.evidenceRefs(), c.diagnosisSnapshotRef(), c.learnerStateSnapshotRef(),
                    c.diagnosisVersion(), c.actionType(), c.interventionVersion(),
                    c.interventionHash() == null
                            ? InterventionRunService.hashIntervention(c.interventionVersion(),
                                    c.actionType(), c.allowedToolIds())
                            : c.interventionHash(),
                    c.allowedToolIds());
        });

        InterventionRun run = scenario.createFromRecommendation(learnerId, rootId);

        assertEquals(InterventionRunScenarioService.ORIGIN, run.origin());
        assertEquals(subjectId, run.subjectId());
        assertEquals(curriculumVersionId, run.curriculumVersionId());
        assertEquals("[\"" + topicId + "\"]", run.targetSpecificationPoints());
        assertEquals("[]", run.questionPartIds());
        assertEquals("[]", run.evidenceRefs());
        assertEquals("nba-rules/v1.3", run.diagnosisVersion());
        assertEquals("nba:nba-rules/v1.3:" + rootId + ":" + topicId + ":rank0:LOW_MASTERY",
                run.diagnosisSnapshotRef());
        assertEquals("skill-state:" + topicId + ":a2:u1700000000000",
                run.learnerStateSnapshotRef());
        assertEquals("PRACTISE_QUESTIONS", run.actionType());
        assertEquals(InterventionRunScenarioService.INTERVENTION_VERSION,
                run.interventionVersion());
        assertEquals(InterventionRunScenarioService.ALLOWED_TOOLS_JSON, run.allowedToolIds());
        // stable intervention identity is derived, not client-supplied
        assertNotNull(run.interventionHash());
        assertEquals(64, run.interventionHash().length());
        assertEquals(InterventionRunStatus.CREATED, run.status());
    }

    @Test
    void unmeasuredTopicIsPinnedHonestlyAsUnmeasuredNeverAsZero() {
        Subject subject = mock(Subject.class);
        CurriculumVersion version = mock(CurriculumVersion.class);
        when(subjects.findByKnowledgeNodeId(rootId)).thenReturn(Optional.of(subject));
        when(subject.id()).thenReturn(subjectId);
        when(subject.curriculumVersion()).thenReturn(version);
        when(version.id()).thenReturn(curriculumVersionId);
        when(nextBestActions.actionsFor(learnerId, rootId)).thenReturn(view(
                new NextBestActionsView.NextBestActionView(0, ActionType.PRACTISE_QUESTIONS,
                        ReasonCode.UNCOVERED_TOPIC, topicId, "4CH1-S2-a", "Group 1",
                        null, 1, "no attempt evidence on this topic")));
        when(skillStates.findByLearnerIdAndNodeId(learnerId, topicId))
                .thenReturn(Optional.empty());
        when(runs.create(any())).thenAnswer(invocation -> {
            InterventionRunService.CreateCommand c = invocation.getArgument(0);
            return new InterventionRun(c.learnerId(), c.subjectId(), c.curriculumVersionId(),
                    c.origin(), c.targetSpecificationPoints(), c.questionPartIds(),
                    c.evidenceRefs(), c.diagnosisSnapshotRef(), c.learnerStateSnapshotRef(),
                    c.diagnosisVersion(), c.actionType(), c.interventionVersion(),
                    "hash", c.allowedToolIds());
        });

        InterventionRun run = scenario.createFromRecommendation(learnerId, rootId);

        assertEquals("skill-state:" + topicId + ":unmeasured", run.learnerStateSnapshotRef());
    }

    @Test
    void noPracticeActionMeansNoRun_FailsClosedWith404Semantics() {
        Subject subject = mock(Subject.class);
        when(subjects.findByKnowledgeNodeId(rootId)).thenReturn(Optional.of(subject));
        when(nextBestActions.actionsFor(learnerId, rootId)).thenReturn(view(
                new NextBestActionsView.NextBestActionView(0, ActionType.ASK_TUTOR,
                        ReasonCode.MISCONCEPTION_SUSPECTED, topicId, "4CH1-S1-a", "x",
                        null, 0, "self-doubt flagged")));

        assertThrows(NotFoundException.class, () -> scenario.createFromRecommendation(learnerId, rootId));
    }

    @Test
    void unknownSubjectRootFailsClosed() {
        when(subjects.findByKnowledgeNodeId(rootId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> scenario.createFromRecommendation(learnerId, rootId));
    }

    private static NextBestActionsView view(NextBestActionsView.NextBestActionView... actions) {
        return new NextBestActionsView(UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                "nba-rules/v1.3", List.of(actions));
    }
}
