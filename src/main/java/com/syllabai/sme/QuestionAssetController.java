package com.syllabai.sme;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing question assets (diagram images referenced by SME question
 * stems/parts/solutions). Authenticated pilot surface — same licensing basis
 * and shape as the revision-note asset endpoint: content by stored filename,
 * no directory semantics, 404 for unknown names.
 */
@RestController
@RequestMapping("/api/v1/content/question-assets")
public class QuestionAssetController {

    private final QuestionAssetRepository assets;

    public QuestionAssetController(QuestionAssetRepository assets) {
        this.assets = assets;
    }

    @GetMapping("/{filename}")
    public ResponseEntity<byte[]> asset(@PathVariable String filename) {
        QuestionAsset asset = assets.findByFilename(filename).orElse(null);
        if (asset == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .contentLength(asset.sizeBytes())
                .body(asset.bytes());
    }
}
