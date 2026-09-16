package com.syllabai.revisionnotes;

import com.syllabai.identity.CurrentUserId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing revision-notes surface (Save-My-Exams-style): tree index with
 * per-subtopic progress input, one note body, authenticated note assets, and
 * the viewed-progress endpoints that drive the rings. All routes are
 * learner-scoped under /api/v1/learners/me; the corpus itself is
 * authenticated-only pilot material (LICENSE-DATA.md).
 */
@RestController
@RequestMapping("/api/v1/learners/me/revision-notes")
public class RevisionNoteLearnerController {

    private final RevisionNoteService notes;

    public RevisionNoteLearnerController(RevisionNoteService notes) {
        this.notes = notes;
    }

    /** The full tree + the caller's viewed markers — one request per view mount. */
    @GetMapping
    public RevisionNoteDtos.RevisionNotesIndexView index(@CurrentUserId UUID learnerId) {
        return notes.index(learnerId);
    }

    @GetMapping("/{noteId}")
    public RevisionNoteDtos.RevisionNoteBodyView body(@PathVariable String noteId) {
        return notes.body(noteId);
    }

    /**
     * Assets are served through an authenticated endpoint (blob-fetched by the
     * frontend); they are corpus-stable so short private caching is safe.
     */
    @GetMapping("/assets/{filename}")
    public ResponseEntity<byte[]> asset(@PathVariable String filename) {
        RevisionNoteAsset asset = notes.asset(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(asset.bytes());
    }

    @GetMapping("/progress")
    public RevisionNoteDtos.RevisionNoteProgressView progress(@CurrentUserId UUID learnerId) {
        return notes.progress(learnerId);
    }

    /** Idempotent: opening the same note twice never conflicts. */
    @PostMapping("/progress/views")
    public ResponseEntity<Void> markViewed(@CurrentUserId UUID learnerId,
            @RequestBody RevisionNoteDtos.MarkNoteViewedRequest request) {
        notes.markViewed(learnerId, request.noteId());
        return ResponseEntity.noContent().build();
    }
}
