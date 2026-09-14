package com.syllabai.teacher;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test Builder endpoints (P9 smallest-useful-version). Route security:
 * /api/v1/teacher/** requires TEACHER/ADMIN — the assembled test and its
 * optional answer key are teacher-only surfaces.
 */
@RestController
@RequestMapping("/api/v1/teacher/tests")
public class TestBuilderController {

    private final TestBuilderService builder;

    public TestBuilderController(TestBuilderService builder) {
        this.builder = builder;
    }

    /**
     * Assemble a printable topic test from VALIDATED content only.
     * Example: GET /api/v1/teacher/tests/preview?rootId=...&topicNodeIds=a,b&maxQuestions=15&includeAnswers=true
     */
    @GetMapping("/preview")
    public TestBuilderService.TestPreviewView preview(
            @RequestParam UUID rootId,
            @RequestParam(required = false) List<UUID> topicNodeIds,
            @RequestParam(required = false) Integer maxQuestions,
            @RequestParam(required = false, defaultValue = "false") boolean includeAnswers) {
        return builder.preview(rootId, topicNodeIds, maxQuestions, includeAnswers);
    }
}
