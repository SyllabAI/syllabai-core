package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-010 §7 review workflow: node validate/reject transitions flip the node and
 * its PART_OF edge; the version gate refuses to activate while any node is
 * SUGGESTED/UNVALIDATED and activates once the whole tree is VALIDATED.
 */
class CurriculumReviewServiceTest {

    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);

    private final CurriculumReviewService service = new CurriculumReviewService(
            curriculumVersions, subjects, knowledgeNodes, knowledgeEdges);

    private final CurriculumVersion version = TestIds.withId(
            new CurriculumVersion("Edexcel", "IAL", "IAL-CHEM-2018",
                    "Edexcel IAL Chemistry", CurriculumVersion.Status.DRAFT),
            UUID.randomUUID());
    private final Subject subject = TestIds.withId(
            new Subject(version, "CH", "Chemistry"), UUID.randomUUID());

    private final Map<UUID, KnowledgeNode> nodesById = new HashMap<>();
    private final Map<UUID, KnowledgeEdge> edgesBySource = new HashMap<>();

    private KnowledgeNode root;
    private KnowledgeNode unit;
    private KnowledgeNode topic;

    {
        when(curriculumVersions.findById(version.id())).thenReturn(Optional.of(version));
        when(curriculumVersions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(version));
        when(subjects.findByCurriculumVersionIdOrderByCode(version.id()))
                .thenReturn(List.of(subject));
        when(knowledgeNodes.save(any(KnowledgeNode.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(knowledgeNodes.findById(any())).thenAnswer(
                inv -> Optional.ofNullable(nodesById.get(inv.getArgument(0))));
        when(knowledgeNodes.findSubtreeIds(any())).thenAnswer(
                inv -> List.copyOf(nodesById.keySet()));
        when(knowledgeEdges.save(any(KnowledgeEdge.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(knowledgeEdges.findBySourceIdAndRelationType(any(), any())).thenAnswer(inv -> {
            UUID sourceId = inv.getArgument(0);
            return Optional.ofNullable(edgesBySource.get(sourceId));
        });

        root = node("IALCHEM2018-ROOT", NodeType.SUBJECT, KnowledgeNode.ValidationStatus.VALIDATED);
        subject.linkKnowledgeNode(root.id());
        unit = node("IALCHEM2018-U1", NodeType.UNIT, KnowledgeNode.ValidationStatus.SUGGESTED);
        topic = node("IALCHEM2018-U1-T3", NodeType.TOPIC, KnowledgeNode.ValidationStatus.SUGGESTED);
        edge(unit, root);
        edge(topic, unit);
    }

    private KnowledgeNode node(String code, NodeType type, KnowledgeNode.ValidationStatus status) {
        KnowledgeNode n = TestIds.withId(new KnowledgeNode(code, type, "title of " + code,
                "description", status, "curriculum:spec-doc-1|method:outline-v1|checksum:1e68fdf7",
                "seed"), UUID.randomUUID());
        nodesById.put(n.id(), n);
        return n;
    }

    private void edge(KnowledgeNode child, KnowledgeNode parent) {
        KnowledgeEdge e = TestIds.withId(new KnowledgeEdge(child, parent, RelationType.PART_OF,
                null, "spec outline hierarchy", KnowledgeNode.ValidationStatus.SUGGESTED,
                "curriculum:spec-doc-1", "seed"), UUID.randomUUID());
        edgesBySource.put(child.id(), e);
    }

    @Test
    @DisplayName("validateNode flips node + its PART_OF edge to VALIDATED")
    void validatesNode() {
        CurriculumReviewService.NodeView view = service.validateNode(topic.id());

        assertThat(view.validationStatus()).isEqualTo("VALIDATED");
        assertThat(topic.validationStatus()).isEqualTo(KnowledgeNode.ValidationStatus.VALIDATED);
        assertThat(edgesBySource.get(topic.id()).validationStatus())
                .isEqualTo(KnowledgeNode.ValidationStatus.VALIDATED);
    }

    @Test
    @DisplayName("rejectNode flips node + edge back to UNVALIDATED")
    void rejectsNode() {
        CurriculumReviewService.NodeView view = service.rejectNode(unit.id());

        assertThat(view.validationStatus()).isEqualTo("UNVALIDATED");
        assertThat(unit.validationStatus()).isEqualTo(KnowledgeNode.ValidationStatus.UNVALIDATED);
        assertThat(edgesBySource.get(unit.id()).validationStatus())
                .isEqualTo(KnowledgeNode.ValidationStatus.UNVALIDATED);
    }

    @Test
    @DisplayName("the version gate refuses activation while SUGGESTED nodes remain")
    void gateRefusesUnvalidatedTree() {
        assertThatThrownBy(() -> service.validateVersion(version.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unvalidated node");
        assertThat(version.status()).isEqualTo(CurriculumVersion.Status.DRAFT);
    }

    @Test
    @DisplayName("the version gate activates once every node is VALIDATED")
    void gateActivatesValidatedTree() {
        service.validateNode(unit.id());
        service.validateNode(topic.id());

        CurriculumReviewService.CurriculumOverview overview =
                service.validateVersion(version.id());

        assertThat(overview.status()).isEqualTo("ACTIVE");
        assertThat(version.status()).isEqualTo(CurriculumVersion.Status.ACTIVE);
        assertThat(overview.validatedNodes()).isEqualTo(3);
    }

    @Test
    @DisplayName("unknown ids fail with NotFoundException, not silent success")
    void unknownIdsFail() {
        assertThatThrownBy(() -> service.validateNode(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.validateVersion(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("review queue lists nodes filtered by status with parent linkage")
    void reviewQueueFilters() {
        AtomicReference<Integer> listed = new AtomicReference<>(0);
        List<CurriculumReviewService.NodeView> suggested =
                service.nodes(version.id(), KnowledgeNode.ValidationStatus.SUGGESTED);
        listed.set(suggested.size());

        assertThat(listed.get()).isEqualTo(2);   // unit + topic, root already VALIDATED
        assertThat(suggested).extracting(CurriculumReviewService.NodeView::code)
                .containsExactly("IALCHEM2018-U1", "IALCHEM2018-U1-T3");
        assertThat(suggested.get(1).parentId()).isEqualTo(unit.id());
    }
}
