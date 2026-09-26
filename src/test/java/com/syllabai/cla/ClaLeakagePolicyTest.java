package com.syllabai.cla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.tutor.EvidenceItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic answer-leakage gate matrix (CLA contract §7.2/§7.3/§7.4),
 * pinned as pure functions: CHECK requires attempt evidence on question
 * contexts; mark-scheme DOCUMENT chunks never enter question-context evidence
 * (page-level chunks cannot be bound to one question); scheme-point evidence
 * is post-attempt and never in HINT; KG_TOPIC evidence behavior is unchanged
 * (tutor parity, step-1 regression).
 */
class ClaLeakagePolicyTest {

    private static final UUID LEARNER = UUID.randomUUID();

    private ResourceContext questionContext(boolean attempted) {
        return new ResourceContext(ResourceContext.Kind.PAST_PAPER_QUESTION,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "4CH1",
                "WCH11-T1.1", "Mole Calculations",
                new ResourceContext.CurriculumVersionInfo("IAL-CHEM-2018", "Edexcel", "IAL",
                        "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                "Calculate the mass of 0.25 mol CaCO3", "Calculate", 2, "4CH0/1C",
                attempted, null, null, null, null);
    }

    private ResourceContext topicContext() {
        return new ResourceContext(ResourceContext.Kind.KG_TOPIC,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and Structure",
                new ResourceContext.CurriculumVersionInfo("IAL-CHEM-2018", "Edexcel", "IAL",
                        "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                null, null, 0, null, null, null, null, null, null);
    }

    /** a SMART_LESSON context: topic-anchored, never assessment content */
    private ResourceContext smartLessonContext() {
        return new ResourceContext(ResourceContext.Kind.SMART_LESSON,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "4CH1",
                "IALCHEM2018-U1-T3", "Bonding and Structure",
                new ResourceContext.CurriculumVersionInfo("IAL-CHEM-2018", "Edexcel", "IAL",
                        "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                null, null, 0, null, null, null,
                new ResourceContext.LessonActionInfo("REVIEW_TOPIC", "DUE_REVIEW",
                        null, null, null, "review due", 3), null, null);
    }

    private EvidenceItem documentMarkSchemeChunk() {
        return new EvidenceItem(EvidenceItem.EvidenceSource.MARK_SCHEME,
                "allow answer in range 24.9–25.1 g",
                UUID.randomUUID(), "4CH0-1C-ms", 1, UUID.randomUUID(), 0,
                null, null, null, null, 6, null, List.of(), List.of(), 0.9, 0.0, null);
    }

    @Test
    @DisplayName("CHECK on a question context requires attempt evidence (pre-attempt throws)")
    void checkPreAttemptThrows() {
        assertThatThrownBy(() -> ClaLeakagePolicy.checkModeAdmission(
                questionContext(false), ResponseMode.CHECK))
                .isInstanceOf(AttemptRequiredException.class);
        // post-attempt: admitted
        assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                questionContext(true), ResponseMode.CHECK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("all other modes are admitted regardless of attempt state — the gate only arms CHECK")
    void otherModesAlwaysAdmitted() {
        for (ResponseMode mode : List.of(ResponseMode.EXPLAIN, ResponseMode.SUMMARIZE,
                ResponseMode.HINT)) {
            assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                    questionContext(false), mode)).doesNotThrowAnyException();
            assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                    topicContext(), mode)).doesNotThrowAnyException();
            assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                    topicContext(), ResponseMode.CHECK)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("mark-scheme DOCUMENT chunks are excluded on question contexts in every mode/attempt state")
    void documentChunksExcludedOnQuestionContexts() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (boolean attempted : new boolean[]{false, true}) {
                assertThat(ClaLeakagePolicy.evidenceEligible(
                        questionContext(attempted), mode, documentMarkSchemeChunk()))
                        .as("mode=%s attempted=%s", mode, attempted)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("KG_TOPIC evidence behavior is unchanged: mark-scheme chunks stay eligible (tutor parity)")
    void topicContextKeepsTutorParity() {
        assertThat(ClaLeakagePolicy.evidenceEligible(
                topicContext(), ResponseMode.EXPLAIN, documentMarkSchemeChunk())).isTrue();
    }

    @Test
    @DisplayName("scheme-point evidence: post-attempt only, never into HINT")
    void schemePointEvidenceMatrix() {
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                questionContext(false), ResponseMode.CHECK)).isFalse();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                questionContext(false), ResponseMode.EXPLAIN)).isFalse();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                questionContext(true), ResponseMode.HINT)).isFalse();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                questionContext(true), ResponseMode.CHECK)).isTrue();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                questionContext(true), ResponseMode.EXPLAIN)).isTrue();
        // KG_TOPIC has no scheme-point evidence at all
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                topicContext(), ResponseMode.CHECK)).isFalse();
    }

    @Test
    @DisplayName("non-mark-scheme evidence (stems, topic nodes, other chunks) is never filtered")
    void ordinaryEvidenceUnaffected() {
        EvidenceItem stem = new EvidenceItem(EvidenceItem.EvidenceSource.QUESTION_PAPER,
                "Question (2 marks): Calculate the mass…",
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), 1.0, 0.0, null);
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(ClaLeakagePolicy.evidenceEligible(
                    questionContext(false), mode, stem)).isTrue();
        }
    }

    // ── QUESTION_PART: the part-level anchor is a question context for the gate ──

    private ResourceContext partContext(boolean attempted) {
        return new ResourceContext(ResourceContext.Kind.QUESTION_PART,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "4CH1",
                "WCH11-T1.1", "Mole Calculations",
                new ResourceContext.CurriculumVersionInfo("IAL-CHEM-2018", "Edexcel", "IAL",
                        "ACTIVE"),
                "VALIDATED", LEARNER, Instant.now(),
                "State why ionic compounds conduct when molten.", "State", 2, "4CH0/1C",
                attempted, "a", null, null, null);
    }

    @Test
    @DisplayName("QUESTION_PART: CHECK requires attempt evidence exactly like the question kind")
    void partCheckRequiresAttempt() {
        assertThatThrownBy(() -> ClaLeakagePolicy.checkModeAdmission(
                partContext(false), ResponseMode.CHECK))
                .isInstanceOf(AttemptRequiredException.class);
        assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                partContext(true), ResponseMode.CHECK)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QUESTION_PART: mark-scheme DOCUMENT chunks are excluded in every mode/attempt state")
    void partDocumentChunksExcluded() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (boolean attempted : new boolean[]{false, true}) {
                assertThat(ClaLeakagePolicy.evidenceEligible(
                        partContext(attempted), mode, documentMarkSchemeChunk()))
                        .as("mode=%s attempted=%s", mode, attempted)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("QUESTION_PART: scheme-point evidence stays post-attempt-only and never into HINT")
    void partSchemePointMatrix() {
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                partContext(false), ResponseMode.CHECK)).isFalse();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                partContext(true), ResponseMode.HINT)).isFalse();
        assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                partContext(true), ResponseMode.CHECK)).isTrue();
    }

    // ── SMART_LESSON: topic-anchored, NOT a question context for the gate ──

    @Test
    @DisplayName("SMART_LESSON: no attempt/assessment boundary of its own — CHECK is admitted like KG_TOPIC")
    void smartLessonHasNoAttemptBoundary() {
        // no invented marking semantics: the lesson context never 409s and never
        // carries the question-context attempt gate
        for (ResponseMode mode : ResponseMode.values()) {
            assertThatCode(() -> ClaLeakagePolicy.checkModeAdmission(
                    smartLessonContext(), mode)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("SMART_LESSON: tutor-parity evidence — mark-scheme chunks stay eligible, no scheme-point evidence")
    void smartLessonTutorParityEvidence() {
        for (ResponseMode mode : ResponseMode.values()) {
            assertThat(ClaLeakagePolicy.evidenceEligible(
                    smartLessonContext(), mode, documentMarkSchemeChunk()))
                    .as("mode=%s", mode).isTrue();
            // the lesson context has no OWN scheme points to unlock (no marking)
            assertThat(ClaLeakagePolicy.schemePointEvidenceAllowed(
                    smartLessonContext(), mode)).isFalse();
        }
        assertThat(smartLessonContext().isQuestionContext()).isFalse();
        assertThat(smartLessonContext().isTopicContext()).isTrue();
    }
}
