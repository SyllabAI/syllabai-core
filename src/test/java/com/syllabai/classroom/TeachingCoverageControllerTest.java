package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.classroom.dto.TeachingCoverageViews.CoverageRowView;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the teaching-coverage surface (V52, TFA-03): the §17
 * ownership gate (another teacher's class is unreachable — list, history,
 * mark all refuse), the fail-closed marking gates (archived class 409,
 * unknown node 404, non-spec-point node 400, invalid status 400), the
 * spec-point gate itself (the V39 invariant: applicability NULL → refuse),
 * the idempotent no-op on identical re-marks, and the one-event-per-change
 * audit rule. HTTP-layer RBAC is pinned separately (TeacherRouteSecurityIT
 * pattern); these pin the object-level rules.
 */
class TeachingCoverageControllerTest {

    private final TeachingCoverageRepository coverage = mock(TeachingCoverageRepository.class);
    private final TeachingCoverageEventRepository events = mock(TeachingCoverageEventRepository.class);
    private final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final TeachingCoverageController controller = new TeachingCoverageController(
            coverage, events, classes, knowledgeNodes);

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID NODE_ID = UUID.randomUUID();
    private static final String COURSE = "igcse-chemistry-19";

    private SchoolClass liveClass() {
        return new SchoolClass(TEACHER, COURSE, "IGCSE Chemistry", "10A") {
            @Override
            public UUID id() {
                return CLASS_ID;
            }
        };
    }

    private SchoolClass archivedClass() {
        SchoolClass c = liveClass();
        c.status(SchoolClass.Status.ARCHIVED);
        return c;
    }

    /** a seed-shaped spec-point node: SUBTOPIC carrying the V39 applicability */
    private KnowledgeNode specPointNode() {
        KnowledgeNode n = mock(KnowledgeNode.class);
        when(n.id()).thenReturn(NODE_ID);
        when(n.nodeType()).thenReturn(NodeType.SUBTOPIC);
        when(n.applicability()).thenReturn(Map.of("papers", List.of("1C")));
        return n;
    }

    private TeachingCoverage row(TeachingCoverage.Status status, String note) {
        return new TeachingCoverage(CLASS_ID, NODE_ID, status, TEACHER, note) {
            @Override
            public UUID specPointNodeId() {
                return NODE_ID;
            }
        };
    }

    private TeachingCoverageController.MarkRequest markRequest(String status, String note) {
        return new TeachingCoverageController.MarkRequest(status, note);
    }

    // ── ownership / reads ───────────────────────────────────────────────

    @Test
    @DisplayName("ownership: unknown class 404, another teacher's class 403 — on every route")
    void ownershipGates() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.list(TEACHER, CLASS_ID))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.history(TEACHER, CLASS_ID, NODE_ID))
                .isInstanceOf(NotFoundException.class);

        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        UUID intruder = UUID.randomUUID();
        assertThatThrownBy(() -> controller.list(intruder, CLASS_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.mark(intruder, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.history(intruder, CLASS_ID, NODE_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("list: only recorded rows — no rows is an honest empty, never fabricated NOT_TAUGHTs")
    void listHonestEmpty() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        when(coverage.findByClassIdOrderBySpecPointNodeIdAsc(CLASS_ID)).thenReturn(List.of());

        assertThat(controller.list(TEACHER, CLASS_ID)).isEmpty();
    }

    // ── marking gates ───────────────────────────────────────────────────

    @Test
    @DisplayName("mark: archived class 409; invalid status 400; unknown node 404")
    void markingGates() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(archivedClass()));
        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("archived");

        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("maybe", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("taught");
        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest(null, null)))
                .isInstanceOf(BadRequestException.class);

        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("specification point");
    }

    @Test
    @DisplayName("THE SPEC-POINT GATE — the V39 invariant: a node without applicability is refused")
    void specPointGate() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode noApplicability = mock(KnowledgeNode.class);
        when(noApplicability.nodeType()).thenReturn(NodeType.SUBTOPIC);
        when(noApplicability.applicability()).thenReturn(null);
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(noApplicability));

        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not a specification point");

        KnowledgeNode notSubtopic = mock(KnowledgeNode.class);
        when(notSubtopic.nodeType()).thenReturn(NodeType.TOPIC);
        when(notSubtopic.applicability()).thenReturn(Map.of("papers", List.of("1C")));
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(notSubtopic));

        assertThatThrownBy(() -> controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not a specification point");

        verify(coverage, never()).save(any());
        verify(events, never()).save(any());
    }

    // ── marking lifecycle ───────────────────────────────────────────────

    @Test
    @DisplayName("first assertion: state row + one audit event with a NULL previous_status")
    void firstAssertion() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode node = specPointNode();
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(coverage.findByClassIdAndSpecPointNodeId(CLASS_ID, NODE_ID)).thenReturn(Optional.empty());
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CoverageRowView view = controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", "done in week 3"));

        assertThat(view.status()).isEqualTo("taught");
        assertThat(view.note()).isEqualTo("done in week 3");
        assertThat(view.specPointNodeId()).isEqualTo(NODE_ID);

        ArgumentCaptor<TeachingCoverageEvent> event = ArgumentCaptor
                .forClass(TeachingCoverageEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().status()).isEqualTo(TeachingCoverage.Status.TAUGHT);
        assertThat(event.getValue().previousStatus()).isNull();
        assertThat(event.getValue().actorId()).isEqualTo(TEACHER);
    }

    @Test
    @DisplayName("identical re-mark (same status AND note) — the honest no-op: no save, no event")
    void identicalRemarkNoOp() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode node = specPointNode();
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(coverage.findByClassIdAndSpecPointNodeId(CLASS_ID, NODE_ID))
                .thenReturn(Optional.of(row(TeachingCoverage.Status.TAUGHT, "done in week 3")));

        CoverageRowView view = controller.mark(TEACHER, CLASS_ID, NODE_ID,
                markRequest("taught", "done in week 3"));

        assertThat(view.status()).isEqualTo("taught");
        verify(coverage, never()).save(any());
        verify(events, never()).save(any());
    }

    @Test
    @DisplayName("status flip: one event carries previous_status → status, state row moves")
    void statusFlipAudited() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode node = specPointNode();
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(coverage.findByClassIdAndSpecPointNodeId(CLASS_ID, NODE_ID))
                .thenReturn(Optional.of(row(TeachingCoverage.Status.TAUGHT, null)));
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CoverageRowView view = controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("not-taught", null));

        assertThat(view.status()).isEqualTo("not-taught");
        ArgumentCaptor<TeachingCoverageEvent> event = ArgumentCaptor
                .forClass(TeachingCoverageEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().status()).isEqualTo(TeachingCoverage.Status.NOT_TAUGHT);
        assertThat(event.getValue().previousStatus()).isEqualTo(TeachingCoverage.Status.TAUGHT);
    }

    @Test
    @DisplayName("note re-assertion on the same status IS information — it is audited, not swallowed")
    void noteReassertionAudited() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode node = specPointNode();
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(coverage.findByClassIdAndSpecPointNodeId(CLASS_ID, NODE_ID))
                .thenReturn(Optional.of(row(TeachingCoverage.Status.TAUGHT, "first note")));
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", "revised note"));

        ArgumentCaptor<TeachingCoverageEvent> event = ArgumentCaptor
                .forClass(TeachingCoverageEvent.class);
        verify(events).save(event.capture());
        assertThat(event.getValue().status()).isEqualTo(TeachingCoverage.Status.TAUGHT);
        assertThat(event.getValue().previousStatus()).isEqualTo(TeachingCoverage.Status.TAUGHT);
        assertThat(event.getValue().note()).isEqualTo("revised note");
    }

    @Test
    @DisplayName("note normalization: blank notes store as NULL, so blank and null are the same assertion")
    void noteNormalization() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        KnowledgeNode node = specPointNode();
        when(knowledgeNodes.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(coverage.findByClassIdAndSpecPointNodeId(CLASS_ID, NODE_ID))
                .thenReturn(Optional.of(row(TeachingCoverage.Status.TAUGHT, null)));

        controller.mark(TEACHER, CLASS_ID, NODE_ID, markRequest("taught", "   "));

        verify(coverage, never()).save(any());
        verify(events, never()).save(any());
    }

    // ── history ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("history: the audit trail served verbatim, newest first from the repo")
    void historyVerbatim() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));
        when(events.findByClassIdAndSpecPointNodeIdOrderByCreatedAtDesc(CLASS_ID, NODE_ID))
                .thenReturn(List.of(
                        new TeachingCoverageEvent(CLASS_ID, NODE_ID,
                                TeachingCoverage.Status.NOT_TAUGHT,
                                TeachingCoverage.Status.TAUGHT, TEACHER, null),
                        new TeachingCoverageEvent(CLASS_ID, NODE_ID,
                                TeachingCoverage.Status.TAUGHT, null, TEACHER, "first")));

        var trail = controller.history(TEACHER, CLASS_ID, NODE_ID);

        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).status()).isEqualTo("not-taught");
        assertThat(trail.get(0).previousStatus()).isEqualTo("taught");
        assertThat(trail.get(1).previousStatus()).isNull();
        assertThat(trail.get(1).note()).isEqualTo("first");
    }
}
