package com.syllabai.assessment;

import com.syllabai.assessment.dto.AttemptResultView;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attempt endpoints (Master Spec §22 — POST /api/v1/attempts).
 */
@RestController
@RequestMapping("/api/v1/attempts")
public class AttemptController {

    private final AssessmentService assessment;

    public AttemptController(AssessmentService assessment) {
        this.assessment = assessment;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttemptResultView submit(@CurrentUserId UUID learnerId,
                                    @Valid @RequestBody SubmitAnswerRequest request) {
        return assessment.submit(learnerId, request);
    }
}
