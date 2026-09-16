package com.syllabai.revisionnotes;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Operator-facing corpus management (role-gated: /api/v1/admin/** requires
 * ADMIN — see SecurityConfig). Ingestion is a full-corpus replace with the
 * ZIP package produced by the corpus generator; status reports what is live.
 */
@RestController
@RequestMapping("/api/v1/admin/revision-notes")
public class RevisionNoteAdminController {

    private final RevisionNoteIngestService ingest;

    public RevisionNoteAdminController(RevisionNoteIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RevisionNoteDtos.RevisionNoteIngestSummary> ingest(
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.syllabai.shared.BadRequestException(
                    "multipart part 'file' (the corpus ZIP) is required");
        }
        try {
            return ResponseEntity.ok(ingest.ingest(file.getBytes()));
        } catch (java.io.IOException e) {
            throw new com.syllabai.shared.BadRequestException(
                    "could not read the uploaded corpus package");
        }
    }

    @GetMapping("/status")
    public RevisionNoteDtos.RevisionNoteStatusView status() {
        return ingest.status();
    }
}
