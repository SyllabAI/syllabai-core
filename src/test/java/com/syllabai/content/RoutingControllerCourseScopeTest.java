package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-030 follow-through: fetch/enumerate accept an optional courseRef and
 * route it through the V53 fail-closed per-course resolution. The pins: a
 * present ref NEVER touches the global resolver; an unresolved ref yields
 * the SAME honest empty view as an unresolved global scope with the service
 * layer never invoked; blank/absent keeps the legacy {@code resolveActive}
 * path. The view contracts are untouched — the empty shape is byte-identical
 * with the T-C07 refusal these endpoints already returned.
 */
class RoutingControllerCourseScopeTest {

    private static final UUID REQUESTER =
            UUID.fromString("00000000-0000-0000-0000-00000000e0c3");
    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c3");
    private static final String REF = "4CH1-2017";
    private static final CurriculumScope SCOPE =
            new CurriculumScope(CV_ID, REF, Set.of());

    private final FetchService fetch = mock(FetchService.class);
    private final EnumerateService enumerate = mock(EnumerateService.class);
    private final CurriculumScopeResolver scopes = mock(CurriculumScopeResolver.class);
    private final RoutingController controller =
            new RoutingController(fetch, enumerate, scopes);

    @Test
    @DisplayName("fetch with courseRef: per-course scope reaches the bank SQL, global resolver never touched")
    void fetchWithCourseRefResolvesPerCourse() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.of(SCOPE));
        when(fetch.fetch("ms Jan 2020 2C", SCOPE))
                .thenReturn(new FetchService.FetchResult(null, false, false, List.of()));

        RoutingController.FetchView view = controller.fetch(REQUESTER, "ms Jan 2020 2C", REF);

        assertThat(view.papers()).isEmpty();
        verify(fetch).fetch("ms Jan 2020 2C", SCOPE);
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("fetch with unresolved ref: honest empty view, bank SQL never runs, NO global fallback")
    void fetchUnresolvedRefFailsClosed() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.empty());

        RoutingController.FetchView view = controller.fetch(REQUESTER, "ms Jan 2020 2C", REF);

        assertThat(view.papers()).isEmpty();
        assertThat(view.parsed()).isNull();
        verify(fetch, never()).fetch(anyString(), any());
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("enumerate with courseRef: per-course scope reaches the bank SQL")
    void enumerateWithCourseRefResolvesPerCourse() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.of(SCOPE));
        EnumerateService.EnumerateResult result =
                new EnumerateService.EnumerateResult("topic", null, null, null, null,
                        false, List.of());
        when(enumerate.enumerate("electrolysis", SCOPE)).thenReturn(result);

        RoutingController.EnumerateView view = controller.enumerate(REQUESTER, "electrolysis", REF);

        assertThat(view.result()).isSameAs(result);
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("enumerate with unresolved ref: empty view, bank SQL never runs, NO global fallback")
    void enumerateUnresolvedRefFailsClosed() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.empty());

        RoutingController.EnumerateView view = controller.enumerate(REQUESTER, "electrolysis", REF);

        assertThat(view.result().mode()).isEqualTo("unscoped");
        assertThat(view.result().questions()).isEmpty();
        verify(enumerate, never()).enumerate(anyString(), any());
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("structured enumerate with courseRef: per-course scope reaches the structured query")
    void structuredEnumerateWithCourseRef() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.of(SCOPE));
        EnumerateService.EnumerateResult result =
                new EnumerateService.EnumerateResult("spec", "1.12", null, 2020, 2021,
                        false, List.of());
        when(enumerate.enumerateStructured("1.12", null, 2020, 2021, null, null, true, SCOPE))
                .thenReturn(result);

        RoutingController.EnumerateView view = controller.enumerateStructured(
                REQUESTER, "1.12", null, 2020, 2021, null, null, "spec", REF);

        assertThat(view.result()).isSameAs(result);
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("blank courseRef is absent: legacy resolveActive path, resolveForCourse never called")
    void blankRefKeepsLegacyPath() {
        when(scopes.resolveActive(REQUESTER)).thenReturn(Optional.of(SCOPE));
        when(fetch.fetch("ms", SCOPE))
                .thenReturn(new FetchService.FetchResult(null, false, false, List.of()));

        RoutingController.FetchView view = controller.fetch(REQUESTER, "ms", "   ");

        assertThat(view.papers()).isEmpty();
        verify(fetch).fetch("ms", SCOPE);
        verify(scopes).resolveActive(REQUESTER);
        verify(scopes, never()).resolveForCourse(anyString());
    }
}
