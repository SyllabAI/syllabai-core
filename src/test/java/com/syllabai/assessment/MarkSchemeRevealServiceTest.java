package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.MarkSchemeRevealView;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mark-scheme reveal boundary: the policy is the whole point. VALIDATED_ONLY
 * (spec-true default) withholds everything not teacher-validated; the
 * operator-authorized INCLUDE_SUGGESTED reveals AI-extracted schemes with their
 * honest state label; REJECTED/FLAGGED never reveal; and reveal inherits the
 * question's servability gate (paper integrity included).
 */
class MarkSchemeRevealServiceTest {

    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID PAPER_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID PART_A_ID = UUID.randomUUID();
    private static final UUID PART_B_ID = UUID.randomUUID();

    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);

    private MarkSchemeRevealService service(String policy) {
        return new MarkSchemeRevealService(servableQuestions, questionVersions, markSchemes, policy);
    }

    private StudentQuestionView question() {
        return new StudentQuestionView(QUESTION_ID, "q01-ext", "STRUCTURED", "Explain the trend.", 6,
                2, 300, "Explain", UUID.randomUUID(), PAPER_ID, List.of(), List.of(), List.of());
    }

    private QuestionVersion version() {
        QuestionVersion version = mock(QuestionVersion.class);
        when(version.id()).thenReturn(VERSION_ID);
        QuestionPart partA = part(PART_A_ID, "(a)", "Describe the bonding.", 3);
        QuestionPart partB = part(PART_B_ID, "(b)", "Explain the difference.", 3);
        when(version.parts()).thenReturn(List.of(partA, partB));
        return version;
    }

    private QuestionPart part(UUID id, String label, String prompt, int marks) {
        QuestionPart part = mock(QuestionPart.class);
        when(part.id()).thenReturn(id);
        when(part.label()).thenReturn(label);
        when(part.prompt()).thenReturn(prompt);
        when(part.marks()).thenReturn(marks);
        return part;
    }

    /** one part-scoped point per part (ordering reversed to prove sorting) + one general */
    private MarkScheme scheme(QuestionVersion version) {
        MarkScheme scheme = new MarkScheme(version, "1", "ms-doc-1", "glm-ocr-extract/v1");
        QuestionPart partA = version.parts().get(0);
        QuestionPart partB = version.parts().get(1);
        scheme.addPoint(new MarkPoint(scheme, partA, "a-i", 2, "ionic lattice", 2, null, null));
        scheme.addPoint(new MarkPoint(scheme, partA, "a-ii", 1, "electrostatic attraction", 1, null, null));
        scheme.addPoint(new MarkPoint(scheme, partB, "b-i", 1, "giant covalent", 3, null, null));
        scheme.addPoint(new MarkPoint(scheme, null, "sp", 1, "correct use of units throughout", 1, null, null));
        return scheme;
    }

    private void stubServable() {
        when(servableQuestions.findById(QUESTION_ID)).thenReturn(Optional.of(question()));
        // build the child mock BEFORE the outer when(...) — Mockito forbids
        // stubbing one mock inside another's unfinished when(...) (house rule,
        // see AssessmentServiceTest)
        QuestionVersion version = version();
        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID))
                .thenReturn(List.of(version));
    }

    @Test
    @DisplayName("VALIDATED_ONLY (default) withholds a SUGGESTED scheme — honest 204 upstream")
    void validatedOnly_hidesSuggested() {
        stubServable();
        MarkScheme scheme = scheme(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID).get(0));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.of(scheme));

        assertThat(service("VALIDATED_ONLY").reveal(QUESTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("INCLUDE_SUGGESTED reveals the AI-extracted scheme with its honest state + grouping")
    void includeSuggested_revealsWithStateAndGrouping() {
        stubServable();
        QuestionVersion version = questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID).get(0);
        MarkScheme scheme = scheme(version); // build BEFORE the outer when(...) — house rule
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.of(scheme));

        Optional<MarkSchemeRevealView> reveal = service("INCLUDE_SUGGESTED").reveal(QUESTION_ID);
        assertThat(reveal).isPresent();
        MarkSchemeRevealView view = reveal.get();
        assertThat(view.validationState()).isEqualTo("SUGGESTED");
        assertThat(view.questionExternalRef()).isEqualTo("q01-ext");
        assertThat(view.schemeMarks()).isEqualTo(7);
        assertThat(view.questionMarks()).isEqualTo(6);

        assertThat(view.parts()).hasSize(2);
        MarkSchemeRevealView.PartScheme partA = view.parts().get(0);
        assertThat(partA.partId()).isEqualTo(PART_A_ID);
        assertThat(partA.points()).extracting(MarkSchemeRevealView.PointView::ref)
                .containsExactly("a-ii", "a-i"); // ordering asc, not insertion order
        assertThat(view.parts().get(1).points()).extracting(MarkSchemeRevealView.PointView::ref)
                .containsExactly("b-i");

        assertThat(view.generalPoints()).hasSize(1);
        assertThat(view.generalPoints().get(0).ref()).isEqualTo("sp");
    }

    @Test
    @DisplayName("a VALIDATED scheme reveals under BOTH policies")
    void validatedScheme_revealsUnderBothPolicies() {
        stubServable();
        QuestionVersion version = questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID).get(0);
        MarkScheme scheme = scheme(version);
        scheme.validate();
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.of(scheme));

        assertThat(service("VALIDATED_ONLY").reveal(QUESTION_ID)).isPresent();
        assertThat(service("INCLUDE_SUGGESTED").reveal(QUESTION_ID)).isPresent()
                .get().extracting(MarkSchemeRevealView::validationState).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("REJECTED and FLAGGED schemes never reveal, even under INCLUDE_SUGGESTED")
    void rejectedOrFlagged_neverReveal() {
        stubServable();
        QuestionVersion version = questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID).get(0);

        MarkScheme rejected = scheme(version);
        rejected.reject();
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.of(rejected));
        assertThat(service("INCLUDE_SUGGESTED").reveal(QUESTION_ID)).isEmpty();

        MarkScheme flagged = scheme(version);
        flagged.flag();
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.of(flagged));
        assertThat(service("INCLUDE_SUGGESTED").reveal(QUESTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("no scheme on the current version, or no version at all — withhold")
    void missingSchemeOrVersion_withholds() {
        stubServable();
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(VERSION_ID))
                .thenReturn(Optional.empty());
        assertThat(service("INCLUDE_SUGGESTED").reveal(QUESTION_ID)).isEmpty();

        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID)).thenReturn(List.of());
        assertThat(service("INCLUDE_SUGGESTED").reveal(QUESTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("a non-servable question has no scheme to show — NotFound (gate inherited)")
    void nonServableQuestion_throwsNotFound() {
        when(servableQuestions.findById(QUESTION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service("INCLUDE_SUGGESTED").reveal(QUESTION_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a bad policy value fails fast at construction — it guards learner content")
    void badPolicyValue_failsFast() {
        assertThatThrownBy(() -> service("SHOW_EVERYTHING"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
