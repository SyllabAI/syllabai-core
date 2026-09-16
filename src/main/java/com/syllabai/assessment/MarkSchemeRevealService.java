package com.syllabai.assessment;

import com.syllabai.assessment.dto.MarkSchemeRevealView;
import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner-facing mark-scheme reveal boundary (Master Spec §15/§20/§22).
 *
 * <p>A mark scheme is serving content like any other, so the reveal runs under
 * an explicit policy instead of an implicit one:</p>
 *
 * <ul>
 *   <li>{@code VALIDATED_ONLY} (default, spec-true): only VALIDATED schemes
 *       reveal; anything else withholds to an honest empty result the
 *       controller answers as 204, so the UI can say "pending teacher
 *       validation" instead of presenting AI-extracted content as
 *       authoritative (§7 posture at the serving boundary).</li>
 *   <li>{@code INCLUDE_SUGGESTED}: SUGGESTED schemes reveal too, carrying
 *       their {@code validationState} so the UI labels them "AI-extracted —
 *       pending teacher validation". An operator-authorized deviation, never
 *       a silent one.</li>
 * </ul>
 *
 * <p>REJECTED and FLAGGED schemes never reveal under either policy (§20 V20
 * semantics: a flagged/rejected scheme must not back anything learner-facing).
 * Reveal also rides the same servability gate as the question itself — paper
 * integrity gate included — so a question that cannot serve has no scheme to
 * show.</p>
 */
@Service
@Transactional(readOnly = true)
public class MarkSchemeRevealService {

    public enum RevealPolicy { VALIDATED_ONLY, INCLUDE_SUGGESTED }

    private final ServableQuestionService servableQuestions;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final RevealPolicy policy;

    public MarkSchemeRevealService(ServableQuestionService servableQuestions,
                                   QuestionVersionRepository questionVersions,
                                   MarkSchemeRepository markSchemes,
                                   @Value("${syllabai.assessment.markscheme-reveal-policy:VALIDATED_ONLY}")
                                   String policy) {
        this.servableQuestions = servableQuestions;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        // fail fast at boot on a bad value — the policy guards learner content
        this.policy = RevealPolicy.valueOf(policy.trim().toUpperCase(Locale.ROOT));
    }

    /** empty when the policy withholds the scheme — the controller answers 204 */
    public Optional<MarkSchemeRevealView> reveal(UUID questionId) {
        StudentQuestionView question = servableQuestions.findById(questionId)
                .orElseThrow(() -> new com.syllabai.shared.NotFoundException("question", questionId));
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(questionId).stream()
                .findFirst().orElse(null);
        if (version == null) {
            return Optional.empty();
        }
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                .orElse(null);
        if (scheme == null) {
            return Optional.empty();
        }
        switch (scheme.validationState()) {
            case REJECTED, FLAGGED -> {
                return Optional.empty();
            }
            case SUGGESTED -> {
                if (policy == RevealPolicy.VALIDATED_ONLY) {
                    return Optional.empty();
                }
            }
            case VALIDATED -> {
                // reveals under both policies
            }
        }
        return Optional.of(project(question, version, scheme));
    }

    RevealPolicy policy() {
        return policy;
    }

    /** part-scoped points grouped under their part (version order); the rest general */
    private MarkSchemeRevealView project(StudentQuestionView question, QuestionVersion version,
                                         MarkScheme scheme) {
        Map<UUID, List<MarkPoint>> byPart = new LinkedHashMap<>();
        List<MarkPoint> general = new ArrayList<>();
        for (MarkPoint point : scheme.points()) {
            UUID partId = point.questionPartId();
            if (partId == null) {
                general.add(point);
            } else {
                byPart.computeIfAbsent(partId, k -> new ArrayList<>()).add(point);
            }
        }
        Comparator<MarkPoint> byOrdering = Comparator.comparingInt(MarkPoint::ordering);
        List<MarkSchemeRevealView.PartScheme> parts = version.parts().stream()
                .map(part -> MarkSchemeRevealView.PartScheme.from(part,
                        byPart.getOrDefault(part.id(), List.of()).stream()
                                .sorted(byOrdering)
                                .map(MarkSchemeRevealView.PointView::from)
                                .toList()))
                .toList();
        List<MarkSchemeRevealView.PointView> generalPoints = general.stream()
                .sorted(byOrdering)
                .map(MarkSchemeRevealView.PointView::from)
                .toList();
        return new MarkSchemeRevealView(
                question.id(), question.externalRef(), scheme.id(),
                scheme.validationState().name(), scheme.totalMarks(), question.marks(),
                parts, generalPoints);
    }
}
