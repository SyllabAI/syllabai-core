package com.syllabai.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C07 scope resolution policy (deterministic, fail-closed): only ACTIVE
 * curriculum versions may own a scope; a candidate owns surface when a subject
 * root subtree carries VALIDATED structure (KG side) or the subject owns exam
 * papers (chunk side); exactly one owner resolves, zero or two owners refuse
 * (never serve across curricula); DRAFT versions and rootless subjects are
 * invisible.
 */
class CurriculumScopeResolverTest {

    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final com.syllabai.assessment.ExamPaperRepository examPapers =
            mock(com.syllabai.assessment.ExamPaperRepository.class);

    private final CurriculumScopeResolver resolver =
            new CurriculumScopeResolver(curriculumVersions, subjects, knowledgeNodes, examPapers);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID cvId = UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private final UUID rootId = UUID.randomUUID();
    private final UUID structureId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CurriculumVersion cv = new CurriculumVersion("Edexcel", "IGCSE", "4CH1-2017",
                "Pearson Edexcel IGCSE Chemistry", CurriculumVersion.Status.ACTIVE);
        setId(cv, "id", cvId);
        when(curriculumVersions.findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status.ACTIVE))
                .thenReturn(List.of(cv));
        when(curriculumVersions.findById(cvId)).thenReturn(Optional.of(cv));
        Subject subject = new Subject(cv, "4CH1", "Chemistry (4CH1)");
        setId(subject, "id", UUID.randomUUID());
        setId(subject, "knowledgeNodeId", rootId);
        when(subjects.findByCurriculumVersionIdOrderByCode(cvId)).thenReturn(List.of(subject));
    }

    @Test
    @DisplayName("the single owning ACTIVE version resolves with its subject-root subtree as the surface")
    void singleOwnerResolves() {
        when(knowledgeNodes.findSubtreeIds(rootId)).thenReturn(List.of(rootId, structureId));
        when(knowledgeNodes.findById(structureId)).thenReturn(Optional.of(
                node(structureId, NodeType.SUBTOPIC, KnowledgeNode.ValidationStatus.VALIDATED)));

        Optional<CurriculumScope> scope = resolver.resolveActive(learnerId);

        assertThat(scope).isPresent();
        assertThat(scope.get().curriculumVersionId()).isEqualTo(cvId);
        assertThat(scope.get().code()).isEqualTo("4CH1-2017");
        assertThat(scope.get().intentSurfaceNodeIds()).containsExactlyInAnyOrder(rootId, structureId);
    }

    @Test
    @DisplayName("no owning surface (nothing VALIDATED, no papers) → empty, never a guess")
    void noOwnerRefuses() {
        when(knowledgeNodes.findSubtreeIds(rootId)).thenReturn(List.of(rootId));
        when(knowledgeNodes.findById(rootId)).thenReturn(Optional.of(
                node(rootId, NodeType.SUBTOPIC, KnowledgeNode.ValidationStatus.SUGGESTED)));
        when(examPapers.existsBySubjectId(any())).thenReturn(false);

        assertThat(resolver.resolveActive(learnerId)).isEmpty();
    }

    @Test
    @DisplayName("paper surface alone is ownership (chunk-only curricula still scope)")
    void paperSurfaceIsOwnership() {
        when(knowledgeNodes.findSubtreeIds(rootId)).thenReturn(List.of(rootId));
        when(knowledgeNodes.findById(rootId)).thenReturn(Optional.of(
                node(rootId, NodeType.TOPIC, KnowledgeNode.ValidationStatus.UNVALIDATED)));
        when(examPapers.existsBySubjectId(any())).thenReturn(true);

        Optional<CurriculumScope> scope = resolver.resolveActive(learnerId);

        assertThat(scope).isPresent();
        assertThat(scope.get().curriculumVersionId()).isEqualTo(cvId);
    }

    @Test
    @DisplayName("SUGGESTED/UNVALIDATED structure is invisible — §7 gate holds at resolution too")
    void suggestedStructureIsInvisible() {
        when(knowledgeNodes.findSubtreeIds(rootId)).thenReturn(List.of(rootId, structureId));
        when(knowledgeNodes.findById(structureId)).thenReturn(Optional.of(
                node(structureId, NodeType.SUBTOPIC, KnowledgeNode.ValidationStatus.SUGGESTED)));
        when(examPapers.existsBySubjectId(any())).thenReturn(false);

        assertThat(resolver.resolveActive(learnerId)).isEmpty();
    }

    @Test
    @DisplayName("rootless subject is no KG owner (fail-closed), paper ownership still decides")
    void rootlessSubjectFailsClosedOnKgSide() {
        Subject rootless = new Subject(
                new CurriculumVersion("Edexcel", "IGCSE", "4CH1-2017", "t", CurriculumVersion.Status.ACTIVE),
                "GEN", "no root linked");
        setId(rootless, "id", UUID.randomUUID());
        when(subjects.findByCurriculumVersionIdOrderByCode(cvId))
                .thenReturn(List.of(subjectWithRoot(), rootless));
        when(knowledgeNodes.findSubtreeIds(rootId)).thenReturn(List.of());
        when(examPapers.existsBySubjectId(any())).thenReturn(true);

        Optional<CurriculumScope> scope = resolver.resolveActive(learnerId);

        assertThat(scope).isPresent();
        assertThat(scope.get().intentSurfaceNodeIds()).isEmpty(); // empty surface matches nothing
    }

    @Test
    @DisplayName("two owning ACTIVE versions → unresolved (ambiguous learner scope is a refusal)")
    void ambiguousOwnersRefuse() {
        UUID secondCvId = UUID.fromString("00000000-0000-0000-0000-0000000002a1");
        CurriculumVersion second = new CurriculumVersion("Edexcel", "IAL", "IAL-CHEM-2018",
                "IAL Chemistry", CurriculumVersion.Status.ACTIVE);
        setId(second, "id", secondCvId);
        UUID secondRoot = UUID.randomUUID();
        Subject secondSubject = new Subject(second, "CHM", "Chemistry (IAL)");
        setId(secondSubject, "id", UUID.randomUUID());
        setId(secondSubject, "knowledgeNodeId", secondRoot);
        when(curriculumVersions.findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status.ACTIVE))
                .thenReturn(List.of(
                        curriculumVersions.findById(cvId).orElseThrow(), second));
        when(subjects.findByCurriculumVersionIdOrderByCode(secondCvId))
                .thenReturn(List.of(secondSubject));
        when(knowledgeNodes.findSubtreeIds(any())).thenReturn(List.of(structureId));
        when(knowledgeNodes.findById(structureId)).thenReturn(Optional.of(
                node(structureId, NodeType.SUBTOPIC, KnowledgeNode.ValidationStatus.VALIDATED)));

        assertThat(resolver.resolveActive(learnerId)).isEmpty();
    }

    @Test
    @DisplayName("DRAFT versions never own a scope even when their subjects carry surface")
    void draftNeverOwns() {
        // only ACTIVE versions are candidates at all — simulate the ACTIVE query
        // returning empty (the DRAFT ingest stubs exist but are not candidates)
        when(curriculumVersions.findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status.ACTIVE))
                .thenReturn(List.of());

        assertThat(resolver.resolveActive(learnerId)).isEmpty();
    }

    private Subject subjectWithRoot() {
        Subject s = new Subject(
                new CurriculumVersion("Edexcel", "IGCSE", "4CH1-2017", "t", CurriculumVersion.Status.ACTIVE),
                "4CH1", "Chemistry (4CH1)");
        setId(s, "id", UUID.randomUUID());
        setId(s, "knowledgeNodeId", rootId);
        return s;
    }

    private KnowledgeNode node(UUID id, NodeType type, KnowledgeNode.ValidationStatus status) {
        KnowledgeNode n = new KnowledgeNode("TEST-" + id, type, "test node", null,
                status, "test", "test");
        setId(n, "id", id);
        return n;
    }

    private static void setId(Object entity, String field, UUID value) {
        try {
            Field f = entity.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
