package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassKnowledgeGraphView;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the F-072 surface gates (the §17 house pattern, the
 * TeachingCoverageControllerTest style): unknown class 404, another
 * teacher's class 403 — and the deliberate ABSENCE of an archived gate: a
 * teacher may inspect a past class's heatmap, because 409 is a write gate
 * and this endpoint is a pure read model.
 */
class ClassKnowledgeGraphControllerTest {

    private final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    private final ClassKnowledgeGraphService heatmaps = mock(ClassKnowledgeGraphService.class);
    private final ClassKnowledgeGraphController controller =
            new ClassKnowledgeGraphController(classes, heatmaps);

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID ROOT = UUID.randomUUID();
    private static final String COURSE = "igcse-chemistry-19";

    @Test
    @DisplayName("ownership gates: unknown class 404, another teacher's class 403")
    void ownershipGates() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.graph(TEACHER, CLASS_ID, ROOT))
                .isInstanceOf(NotFoundException.class);

        when(classes.findById(CLASS_ID))
                .thenReturn(Optional.of(ownedClass()));
        assertThatThrownBy(() -> controller.graph(UUID.randomUUID(), CLASS_ID, ROOT))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("archived classes stay READABLE — 409 is a write gate, the heatmap is a read model")
    void archivedClassIsReadable() {
        SchoolClass archived = ownedClass();
        archived.status(SchoolClass.Status.ARCHIVED);
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(archived));
        ClassKnowledgeGraphView view = new ClassKnowledgeGraphView(
                CLASS_ID, "10A", ROOT, "4CH1", "Edexcel IGCSE Chemistry 4CH1",
                0, Instant.now(), List.of(), List.of());
        when(heatmaps.graph(archived, ROOT)).thenReturn(view);

        assertThat(controller.graph(TEACHER, CLASS_ID, ROOT)).isSameAs(view);
    }

    private SchoolClass ownedClass() {
        return new SchoolClass(TEACHER, COURSE, "IGCSE Chemistry", "10A") {
            @Override
            public UUID id() {
                return CLASS_ID;
            }
        };
    }
}
