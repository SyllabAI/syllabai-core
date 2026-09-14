package com.syllabai.teacher;

import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * V22 durable audit for API-driven content-review mutations. The importer path
 * keeps its hash-chained {@code teacher_validation_events}; this recorder is
 * the API counterpart — every validate/reject/flag/unflag/place/map decision
 * made through {@code /api/v1/teacher/content/**} leaves a permanent,
 * append-only row in {@code content_review_audit}, written in the SAME
 * transaction as the mutation.
 *
 * <p>Actor resolution: the JWT subject (the reviewer's email) from the
 * security context. When no authenticated principal exists (system-context
 * calls, unit tests) the row is recorded with a {@code system} label — the
 * mutation still happened and must still be auditable.</p>
 */
@Component
public class ContentAuditRecorder {

    private final ContentReviewAuditRepository audit;
    private final UserRepository users;

    public ContentAuditRecorder(ContentReviewAuditRepository audit, UserRepository users) {
        this.audit = audit;
        this.users = users;
    }

    /** the authenticated reviewer's email, or null outside a request context */
    public String currentActorLabel() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() || "anonymousUser".equals(name) ? null : name;
    }

    /**
     * Persist one audit row in the caller's transaction. FAIL-CLOSED: if the
     * row cannot be persisted the exception propagates and the mutation rolls
     * back — the contract "no promotion without an audit row" is stronger than
     * review-path availability (same philosophy as V18's importer gate).
     */
    public void record(String action, String targetType, UUID targetId,
                       String fromState, String toState, String detail) {
        String label = currentActorLabel();
        User actor = label == null ? null
                : users.findByEmailIgnoreCase(label).orElse(null);
        audit.save(new ContentReviewAudit(actor, action, targetType, targetId,
                fromState, toState, detail));
    }
}
