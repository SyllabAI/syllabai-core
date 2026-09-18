package com.syllabai.sme;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Operator-facing SME question-bank management (role-gated:
 * /api/v1/admin/** requires ADMIN — see SecurityConfig). Ingestion is the
 * ADR-026 replace: deactivate the live bank, insert the SME corpus, one
 * transaction, evidence-safe.
 */
@RestController
@RequestMapping("/api/v1/admin/question-bank")
public class SmeQuestionAdminController {

    private final SmeQuestionIngestService ingest;

    public SmeQuestionAdminController(SmeQuestionIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SmeQuestionPackageDtos.IngestSummary> ingest(
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
    public BankStatusView status() {
        return ingest.status();
    }

    /** live bank snapshot: what is serving right now */
    public record BankStatusView(
            long activeQuestions,
            long activeMcq,
            long activeStructured,
            long specPointMappings,
            long assets) {
    }
}
