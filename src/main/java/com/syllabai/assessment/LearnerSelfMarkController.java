package com.syllabai.assessment;

import com.syllabai.identity.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing self-marking (ADR-026 SME practice tranche): the
 * Save-My-Exams-style "reveal the mark scheme and self-mark" step that
 * follows a structured submission. Learner-scoped under
 * /api/v1/learners/me — the service re-verifies ownership against the
 * authenticated caller, so another learner's attempt id is a plain 404.
 */
@RestController
@RequestMapping("/api/v1/learners/me/attempts")
public class LearnerSelfMarkController {

    private final LearnerSelfMarkService selfMarking;

    public LearnerSelfMarkController(LearnerSelfMarkService selfMarking) {
        this.selfMarking = selfMarking;
    }

    @PostMapping("/{attemptId}/self-mark")
    @ResponseStatus(HttpStatus.CREATED)
    public SelfMarkView selfMark(@CurrentUserId UUID learnerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody SelfMarkRequest request) {
        Map<UUID, Integer> marksByPartId = new HashMap<>();
        for (PartSelfMark p : request.parts()) {
            if (marksByPartId.put(p.partId(), p.marksAwarded()) != null) {
                throw new com.syllabai.shared.BadRequestException(
                        "duplicate part in self-mark: " + p.partId());
            }
        }
        LearnerSelfMarkService.SelfMarkResult result =
                selfMarking.selfMark(learnerId, attemptId, marksByPartId, request.comment());
        return SelfMarkView.from(result);
    }

    public record PartSelfMark(
            @NotNull UUID partId,
            @NotNull @Min(0) @Max(99) Integer marksAwarded) {
    }

    public record SelfMarkRequest(List<PartSelfMark> parts, String comment) {
    }

    public record SelfMarkView(UUID attemptId, int marksAwarded, int marksTotal,
            boolean evidenceFired, List<PartView> parts) {

        static SelfMarkView from(LearnerSelfMarkService.SelfMarkResult result) {
            List<PartView> parts = result.answers().stream()
                    .map(a -> new PartView(a.questionPartId(),
                            a.questionPart().label(), a.marksAwarded(),
                            a.questionPart().marks(), a.markingState().name()))
                    .toList();
            return new SelfMarkView(result.attempt().id(),
                    result.attempt().marksAwarded() == null ? 0 : result.attempt().marksAwarded(),
                    result.attempt().question().marks(),
                    result.evidenceFired(), parts);
        }
    }

    public record PartView(UUID partId, String label, int marksAwarded,
            int marksPossible, String markingState) {
    }
}
