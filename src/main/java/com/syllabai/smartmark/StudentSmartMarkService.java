package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.MarkSchemeRevealService;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.SmartFeedbackExplainedEvent;
import com.syllabai.shared.events.SmartImprovementPlanViewedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The student-facing Smart Mark surface (Master Spec §15; the learner half of
 * F-047): after submitting a structured attempt the learner can open
 * <em>Smart Mark</em> on their own answers — the SAME marking pipeline the
 * teacher queue runs ({@link SmartMarkService}: candidate generation pinned to
 * the scheme's mark points + acceptance criteria, deterministic validators,
 * append-only results, κ release gate) — and then consume the two feedback
 * actions, "Explain my feedback" and "Improve my answer", which are generated
 * from the recorded decisions, never from the raw scheme.
 *
 * <p>Boundaries this service owns (mirroring the ADR-026 tranche's
 * conventions):</p>
 *
 * <ul>
 *   <li><b>One engine.</b> Student runs mark through
 *       {@link SmartMarkService#markAnswer} unchanged — identical prompts,
 *       validators, result rows, κ pairing and telemetry. The student surface
 *       adds ownership checks, never a second pipeline.</li>
 *   <li><b>Reveal-consistent.</b> Smart marking is learner-facing marking, so
 *       the scheme must pass the SAME reveal policy as the mark-scheme reveal
 *       boundary ({@code syllabai.assessment.markscheme-reveal-policy},
 *       default VALIDATED_ONLY). Withheld scheme → 409, the same honest
 *       "pending teacher validation" state the UI already knows.</li>
 *   <li><b>Pre-settlement only.</b> Smart Mark runs while every part is
 *       PENDING/SMART_MARKED; once a self-mark or teacher mark settles the
 *       attempt, the AI step no longer rewrites marks (single-settlement
 *       semantics across all three marking paths; teacher override remains the
 *       only revision path).</li>
 *   <li><b>κ-gate honesty.</b> The response carries {@code authoritative} so
 *       the UI can say whether the marks drove mastery evidence (gate passed)
 *       or are provisional feedback (pre-gate) — never a silent either.</li>
 *   <li><b>Ephemeral feedback prose.</b> Explanations/improvement plans are
 *       regenerated per request and NOT persisted — the durable artifact is
 *       the marking breakdown; consumption lands as research telemetry events
 *       instead.</li>
 * </ul>
 */
@Service
public class StudentSmartMarkService {

    private static final Logger log = LoggerFactory.getLogger(StudentSmartMarkService.class);

    private final AttemptRepository attempts;
    private final AnswerRepository answers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final SmartMarkService smartMarkService;
    private final SmartMarkResultRepository smartMarkResults;
    private final LlmProvider llm;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final MarkSchemeRevealService.RevealPolicy revealPolicy;

    public StudentSmartMarkService(AttemptRepository attempts,
                                   AnswerRepository answers,
                                   QuestionVersionRepository questionVersions,
                                   MarkSchemeRepository markSchemes,
                                   SmartMarkService smartMarkService,
                                   SmartMarkResultRepository smartMarkResults,
                                   LlmProvider llm,
                                   org.springframework.context.ApplicationEventPublisher events,
                                   @Value("${syllabai.assessment.markscheme-reveal-policy:VALIDATED_ONLY}")
                                   String revealPolicy) {
        this.attempts = attempts;
        this.answers = answers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.smartMarkService = smartMarkService;
        this.smartMarkResults = smartMarkResults;
        this.llm = llm;
        this.events = events;
        this.revealPolicy = parsePolicy(revealPolicy);
    }

    private static MarkSchemeRevealService.RevealPolicy parsePolicy(String raw) {
        try {
            return MarkSchemeRevealService.RevealPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "unknown markscheme-reveal-policy '" + raw + "' — expected VALIDATED_ONLY or INCLUDE_SUGGESTED");
        }
    }

    /**
     * Smart-mark every markable part of the caller's structured attempt in one
     * pass (the reveal/self-mark tranche's "one full pass" shape). Transactional:
     * the attempt row lock serializes against self/teacher marking, and the
     * whole pass is atomic — a mid-pass LLM failure rolls back every provisional
     * state change, leaving no half-marked attempt behind.
     *
     * @throws NotFoundException   unknown attempt, or another learner's attempt
     *                             (no existence leak)
     * @throws BadRequestException non-structured attempt or no part answers
     * @throws ConflictException   attempt already settled, or the scheme is
     *                             withheld by the reveal policy
     */
    @Transactional
    public StudentSmartMarkViews.AttemptSmartMarkView smartMarkAttempt(UUID learnerId, UUID attemptId) {
        // settle-state serialization point: the attempt row lock is held for the
        // whole pass (TeacherMarkingService/LearnerSelfMarkService precedent)
        Attempt attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new NotFoundException("attempt", attemptId));
        if (!attempt.learnerId().equals(learnerId)) {
            // no existence leak across learners
            throw new NotFoundException("attempt", attemptId);
        }
        Question question = attempt.question();
        if (question.type() != Question.Type.STRUCTURED) {
            throw new BadRequestException("smart marking applies to structured attempts only");
        }
        List<Answer> attemptAnswers = answers.findByAttemptIdOrderByQuestionPartId(attempt.id());
        if (attemptAnswers.isEmpty()) {
            throw new BadRequestException("attempt carries no part answers");
        }
        for (Answer answer : attemptAnswers) {
            Answer.MarkingState state = answer.markingState();
            if (state != Answer.MarkingState.PENDING && state != Answer.MarkingState.SMART_MARKED) {
                throw new ConflictException("attempt already settled (" + state
                        + ") — Smart Mark runs before a self-mark or teacher mark settles it");
            }
        }
        MarkScheme scheme = resolveScheme(question.id());
        requireRevealableScheme(scheme);

        List<StudentSmartMarkViews.PartSmartMarkView> parts = new ArrayList<>();
        boolean authoritative = smartMarkService.kappaGatePassed(question.examPaperId());
        for (Answer answer : attemptAnswers) {
            // the SAME engine the teacher queue runs — one pipeline, one
            // calibration dataset, one telemetry stream
            SmartMarkResult result = smartMarkService.markAnswer(answer.id());
            parts.add(project(answer, result, scheme, authoritative));
        }
        log.info("student smart mark pass: attempt {} ({} parts, authoritative={}) by learner {}",
                attemptId, parts.size(), authoritative, learnerId);
        return new StudentSmartMarkViews.AttemptSmartMarkView(
                attempt.id(), question.id(), scheme.validationState().name(),
                question.marks(), List.copyOf(parts));
    }

    /**
     * "Explain my feedback": a grounded walk-through of the recorded per-point
     * decisions for one part — quoting the student's own evidence — generated
     * from the accepted Smart Mark result. Deterministic guard: an accepted
     * result must exist (409 otherwise); the LLM explains decisions, it never
     * makes them.
     */
    public StudentSmartMarkViews.FeedbackExplanationView explainFeedback(
            UUID learnerId, UUID attemptId, UUID partId) {
        FeedbackSource source = loadFeedbackSource(learnerId, attemptId, partId);
        String text = generate(explainSystemPrompt(), explainUserPrompt(source), 0.2);
        events.publishEvent(new SmartFeedbackExplainedEvent(
                source.answer().id(), source.attempt().id(), learnerId,
                source.attempt().question().id(), source.result().id(),
                source.modelId(), Instant.now()));
        log.info("feedback explanation generated for answer {} (model {})",
                source.answer().id(), source.modelId());
        return new StudentSmartMarkViews.FeedbackExplanationView(
                partId, text, source.modelId(), Instant.now());
    }

    /**
     * "Improve my answer": coaching toward the not-awarded mark points — what
     * was missing from the student's answer and one concrete step per missing
     * point. Coaching, never answer-writing: the prompt forbids producing a
     * finished answer or dumping the scheme.
     */
    public StudentSmartMarkViews.ImprovementPlanView improvementPlan(
            UUID learnerId, UUID attemptId, UUID partId) {
        FeedbackSource source = loadFeedbackSource(learnerId, attemptId, partId);
        String text = generate(improveSystemPrompt(), improveUserPrompt(source), 0.3);
        events.publishEvent(new SmartImprovementPlanViewedEvent(
                source.answer().id(), source.attempt().id(), learnerId,
                source.attempt().question().id(), source.result().id(),
                source.modelId(), Instant.now()));
        log.info("improvement plan generated for answer {} (model {})",
                source.answer().id(), source.modelId());
        return new StudentSmartMarkViews.ImprovementPlanView(
                partId, text, source.modelId(), Instant.now());
    }

    // ── grounding ────────────────────────────────────────────────────────

    private record FeedbackSource(Attempt attempt, Answer answer,
                                  SmartMarkResult result, List<MarkPoint> points,
                                  String modelId) {
    }

    private FeedbackSource loadFeedbackSource(UUID learnerId, UUID attemptId, UUID partId) {
        Attempt attempt = attempts.findById(attemptId)
                .filter(a -> a.learnerId().equals(learnerId))
                .orElseThrow(() -> new NotFoundException("attempt", attemptId));
        Answer answer = answers.findByAttemptIdOrderByQuestionPartId(attempt.id()).stream()
                .filter(a -> a.questionPartId().equals(partId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("part", partId));
        SmartMarkResult result = smartMarkResults.findLatest(answer.id())
                .filter(SmartMarkResult::validationPassed)
                .orElseThrow(() -> new ConflictException(
                        "no accepted Smart Mark result for this part yet — run Smart mark first"));
        MarkScheme scheme = resolveScheme(attempt.question().id());
        List<MarkPoint> points = scheme.points().stream()
                .filter(p -> answer.questionPartId().equals(p.questionPartId()))
                .toList();
        return new FeedbackSource(attempt, answer, result, points, result.modelId());
    }

    private MarkScheme resolveScheme(UUID questionId) {
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(questionId).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", questionId));
        return markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                .orElseThrow(() -> new NotFoundException("mark scheme", version.id()));
    }

    /**
     * The reveal-policy mirror: smart marking is learner-facing marking, so the
     * scheme passes the same boundary as the mark-scheme reveal
     * ({@code MarkSchemeRevealService} — keep the two switches aligned by
     * property, one lever for the operator). REJECTED/FLAGGED never pass.
     */
    private void requireRevealableScheme(MarkScheme scheme) {
        switch (scheme.validationState()) {
            case REJECTED, FLAGGED -> throw new ConflictException(
                    "mark scheme is not servable — Smart Mark is unavailable for this question");
            case SUGGESTED -> {
                if (revealPolicy == MarkSchemeRevealService.RevealPolicy.VALIDATED_ONLY) {
                    throw new ConflictException(
                            "mark scheme is pending teacher validation — Smart Mark is unavailable until it validates");
                }
            }
            case VALIDATED -> {
                // passes under both policies
            }
        }
    }

    private StudentSmartMarkViews.PartSmartMarkView project(Answer answer, SmartMarkResult result,
                                                            MarkScheme scheme, boolean authoritative) {
        Map<UUID, MarkPoint> byId = new LinkedHashMap<>();
        for (MarkPoint point : scheme.points()) {
            if (answer.questionPartId().equals(point.questionPartId())) {
                byId.put(point.id(), point);
            }
        }
        List<StudentSmartMarkViews.PointDecisionView> breakdown = new ArrayList<>();
        if (result.breakdown() != null) {
            for (Map<String, Object> entry : result.breakdown()) {
                UUID pointId = UUID.fromString(String.valueOf(entry.get("markPointId")));
                MarkPoint point = byId.get(pointId);
                int pointMarks = point == null ? 0 : point.marks();
                int awardedMarks = awardedMarks(entry, pointMarks);
                breakdown.add(new StudentSmartMarkViews.PointDecisionView(
                        String.valueOf(entry.get("ref")),
                        point == null ? String.valueOf(entry.get("ref")) : pointLabel(point),
                        pointMarks,
                        awardedMarks,
                        Boolean.TRUE.equals(entry.get("awarded")),
                        String.valueOf(entry.getOrDefault("evidence", "")),
                        String.valueOf(entry.getOrDefault("rationale", ""))));
            }
        }
        return new StudentSmartMarkViews.PartSmartMarkView(
                answer.questionPartId(),
                answer.questionPart().label(),
                result.marksAwarded(),
                answer.questionPart().marks(),
                answer.markingState().name(),
                authoritative,
                result.confidence(),
                result.modelId(),
                result.validationPassed(),
                result.failureReason(),
                List.copyOf(breakdown));
    }

    /**
     * v1.0/v1.1 result rows carry boolean-only decisions (whole-point semantics);
     * v1.2 rows carry explicit partial {@code marksAwarded}. Read the explicit
     * value when present, fall back to boolean × point marks otherwise.
     */
    private static int awardedMarks(Map<String, Object> entry, int pointMarks) {
        Object explicit = entry.get("marksAwarded");
        if (explicit instanceof Number n) {
            return Math.max(0, Math.min(n.intValue(), pointMarks));
        }
        return Boolean.TRUE.equals(entry.get("awarded")) ? pointMarks : 0;
    }

    /**
     * Compact, scheme-leak-safe label for one mark point: the first meaningful
     * line of the scheme text with markdown decorations stripped, capped at 90
     * characters. The Smart Mark flow deliberately does NOT reveal the scheme —
     * the full text (model answers, teaching notes) stays server-side and feeds
     * only the explain/improve generations.
     */
    static String pointLabel(MarkPoint point) {
        String text = point.text() == null ? "" : point.text();
        for (String line : text.split("\\R")) {
            String cleaned = line.replaceAll("^[-*+>\\s]+", "")
                    .replaceAll("\\*+", "")
                    .replaceAll("<sub>[^<]*</sub>|<sup>[^<]*</sup>", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleaned.isEmpty() || cleaned.startsWith("[Total")
                    || cleaned.matches("^\\[.*\\]$")) {
                continue;
            }
            return cleaned.length() > 90 ? cleaned.substring(0, 89) + "…" : cleaned;
        }
        return String.valueOf(point.ref());
    }

    // ── generation ───────────────────────────────────────────────────────

    private String generate(String systemPrompt, String userPrompt, double temperature) {
        if (!llm.available()) {
            throw new SmartFeedbackGenerationException(
                    "the marking feedback engine is temporarily unavailable — try again shortly");
        }
        try {
            LlmResponse response = llm.generate(
                    LlmRequest.withOptions(systemPrompt, userPrompt, temperature, 700));
            String text = response.text();
            if (text == null || text.isBlank()) {
                throw new SmartFeedbackGenerationException(
                        "feedback generation returned an empty response — try again");
            }
            return text.trim();
        } catch (LlmProviderException e) {
            throw new SmartFeedbackGenerationException(
                    "feedback generation failed — try again shortly", e);
        }
    }

    String explainSystemPrompt() {
        return """
                You are an exam tutor explaining a marking result to an IGCSE student.
                You are given the question part, the student's answer, and the final
                per-mark-point decisions (including partial marks) produced by the
                marking pipeline. Structure your explanation EXACTLY like this:
                1. One opening sentence: the marks earned out of the marks possible.
                2. One short paragraph per mark point, in the order given, opening
                   with the point's marks (e.g. \"b: 1 of 3\") — say what earned
                   the credit (quoting the student's own words) and, for lost
                   marks, exactly which sub-point content was missing.
                3. If nothing was earned on a point, one sentence naming the
                   specific missing content — never a generic fact dump.
                Never change a mark decision, never invent mark points, never add
                requirements beyond the provided decisions. Be specific and
                encouraging. Under 200 words. Plain text; short paragraphs; no
                markdown headings.
                """;
    }

    String explainUserPrompt(FeedbackSource source) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, source);
        sb.append("\nTASK: Explain this marking result to the student — point by point,\n")
                .append("in the order above. Start with one sentence on the overall result.\n");
        return sb.toString();
    }

    String improveSystemPrompt() {
        return """
                You are an exam coach helping an IGCSE student improve a marked answer.
                You receive the question part, the student's answer, and the final
                per-mark-point decisions (including partial marks). Structure your
                coaching EXACTLY like this:
                1. One opening sentence naming the marks still available (the gap
                   between marks earned and marks possible).
                2. For every point that lost marks — whole or partial — one short
                   paragraph: what the student wrote vs. what the examiner needed
                   for the missing sub-point, then ONE concrete actionable step
                   phrased so the student could earn it next time. Stay tied to
                   the missing sub-points; no general topic summaries, no facts
                   the missing marks do not depend on.
                3. If every mark was awarded, give one examiner-technique tip to
                   make the answer examiner-proof instead.
                Coach — do NOT write a finished answer for the student, do NOT
                quote the mark scheme verbatim, do NOT change any mark decision.
                Under 200 words. Plain text; short paragraphs; no markdown headings.
                """;
    }

    String improveUserPrompt(FeedbackSource source) {
        StringBuilder sb = new StringBuilder();
        appendContext(sb, source);
        sb.append("\nTASK: Coach the student toward the missing marks (or give one\n")
                .append("examiner-technique tip if everything was awarded).\n");
        return sb.toString();
    }

    private void appendContext(StringBuilder sb, FeedbackSource source) {
        var part = source.answer().questionPart();
        sb.append("QUESTION PART (").append(part.label()).append("):\n")
                .append(part.prompt()).append("\n\nSTUDENT ANSWER:\n")
                .append(source.answer().answerText()).append("\n\nMARKING DECISIONS:\n");
        Map<UUID, MarkPoint> byId = new LinkedHashMap<>();
        for (MarkPoint point : source.points()) {
            byId.put(point.id(), point);
        }
        for (Map<String, Object> entry : source.result().breakdown()) {
            UUID pointId = UUID.fromString(String.valueOf(entry.get("markPointId")));
            MarkPoint point = byId.get(pointId);
            int pointMarks = point == null ? 0 : point.marks();
            int earned = awardedMarks(entry, pointMarks);
            sb.append("- ref=").append(entry.get("ref"))
                    .append(" marks earned=").append(earned)
                    .append(" of ").append(pointMarks).append('\n');
            if (point != null) {
                sb.append("  point: ").append(point.text()).append('\n');
            }
            String evidence = String.valueOf(entry.getOrDefault("evidence", ""));
            if (!evidence.isBlank()) {
                sb.append("  student evidence quoted: \"").append(evidence).append("\"\n");
            }
            sb.append("  marker rationale: ")
                    .append(entry.getOrDefault("rationale", "")).append('\n');
        }
    }
}
