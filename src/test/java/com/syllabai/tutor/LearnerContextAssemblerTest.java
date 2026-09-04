package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.SkillState;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MatchedTopic;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Learner-aware context assembly (T-024): mastery/misconceptions/fluency-gap
 * briefs render for the matched topics only; anonymous calls get an explicit
 * empty brief; no prior evidence renders honestly.
 */
class LearnerContextAssemblerTest {

    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final LearnerContextAssembler assembler = new LearnerContextAssembler(learnerModel);

    private final UUID topicId = UUID.randomUUID();
    private final UUID misconceptionId = UUID.randomUUID();
    private final UUID learnerId = UUID.randomUUID();

    private final KnowledgeContext knowledge = new KnowledgeContext(
            List.of(new MatchedTopic(topicId, "IALCHEM2018-U1-T3", "Bonding and Structure", 0.5)),
            List.of(),
            List.of(new KnowledgeContext.MisconceptionSignal(topicId, misconceptionId,
                    "Moles and grams are interchangeable")));

    @Test
    @DisplayName("anonymous assembly renders an explicit no-state brief")
    void anonymousBrief() {
        ContextAssembler.TutorContext context = assembler.assemble(knowledge, List.of(), null);

        assertThat(context.learnerBrief()).contains("not available");
        assertThat(context.knowledgeBrief()).contains("Bonding and Structure");
        assertThat(context.knowledgeBrief()).contains(
                "Known misconceptions attached to these topics");
        assertThat(context.evidence()).isEmpty();
    }

    @Test
    @DisplayName("mastery + active misconception + fluency gap render for relevant topics")
    void learnerBriefRenders() throws Exception {
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(
                skill(topicId, 0.72, 0.31),
                skill(UUID.randomUUID(), 0.9, null)));     // irrelevant node — filtered out
        when(learnerModel.misconceptionStates(learnerId)).thenReturn(List.of(
                misconception(misconceptionId, 0.75),
                misconception(UUID.randomUUID(), 0.1)));   // inactive — filtered out

        ContextAssembler.TutorContext context =
                assembler.assemble(knowledge, List.of(), learnerId);

        assertThat(context.learnerBrief()).contains("mastery of 'Bonding and Structure': 0.72");
        assertThat(context.learnerBrief()).contains("active misconception: Moles and grams");
        assertThat(context.learnerBrief()).contains("fluency gap on a relevant topic: +0.31");
        assertThat(context.learnerBrief()).doesNotContain("0.9");   // irrelevant mastery excluded
    }

    @Test
    @DisplayName("no prior evidence renders honestly, never a fabricated profile")
    void noEvidenceRendersHonestly() {
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(learnerId)).thenReturn(List.of());

        ContextAssembler.TutorContext context =
                assembler.assemble(knowledge, List.of(), learnerId);
        assertThat(context.learnerBrief())
                .isEqualTo("Learner state: no prior evidence on the topics in this question.");
    }

    private SkillState skill(UUID nodeId, double mastery, Double fluencyGap) throws Exception {
        SkillState state = new SkillState(learnerId, nodeId, 0.1, Instant.now());
        Field masteryField = SkillState.class.getDeclaredField("mastery");
        masteryField.setAccessible(true);
        masteryField.set(state, mastery);
        if (fluencyGap != null) {
            Field gapField = SkillState.class.getDeclaredField("proceduralFluencyGap");
            gapField.setAccessible(true);
            gapField.set(state, fluencyGap);
        }
        return state;
    }

    private MisconceptionState misconception(UUID nodeId, double probability) throws Exception {
        MisconceptionState state =
                new MisconceptionState(learnerId, nodeId, 0.3, Instant.now());
        Field field = MisconceptionState.class.getDeclaredField("probability");
        field.setAccessible(true);
        field.set(state, probability);
        return state;
    }
}
