package com.syllabai.tutor;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.tutor.dto.TutorAnswerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tutor ask endpoint (T-024 backend surface — the chat UI itself is T-025 and
 * deliberately not built yet; this endpoint exists so the pipeline is
 * testable, research-instrumented and consumable before any UI work).
 * Authenticated: students ask, teachers preview with their own identity.
 */
@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {

    private final KaRagService kaRag;

    public TutorController(KaRagService kaRag) {
        this.kaRag = kaRag;
    }

    public record TutorAskRequest(
            @NotBlank @Size(max = 2000) String question) {
    }

    @PostMapping("/ask")
    public TutorAnswerView ask(@CurrentUserId UUID learnerId,
                               @Valid @RequestBody TutorAskRequest request) {
        return kaRag.ask(learnerId, request.question());
    }
}
