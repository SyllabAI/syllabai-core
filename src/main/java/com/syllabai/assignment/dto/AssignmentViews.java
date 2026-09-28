package com.syllabai.assignment.dto;

import com.syllabai.assignment.Assignment;
import com.syllabai.assignment.AssignmentSubmission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire views for the assignments contract (V49). Shapes are additive and
 * deploy-skew safe: the hub treats unknown extra keys as pass-through and
 * missing optional fields as absent (the established cross-repo rule).
 */
public interface AssignmentViews {

    /** the assignment as both sides see it (canonical wire vocabulary) */
    record AssignmentView(
            UUID id,
            String title,
            String courseSlug,
            String courseLabel,
            List<String> specRefs,
            int marksTotal,
            int questionCount,
            Instant dueAt,
            String status,
            Instant createdAt) {

        public static AssignmentView from(Assignment a) {
            return new AssignmentView(a.id(), a.title(), a.courseSlug(), a.courseLabel(),
                    a.specRefs(), a.marksTotal(), a.questionCount(), a.dueAt(),
                    a.status().wire(), a.createdAt());
        }
    }

    /** list-row stats: real completion counts over the enabled STUDENT cohort */
    record AssignmentSummaryView(
            AssignmentView assignment,
            int submitted,
            int late,
            int missing,
            Double meanScore) {
    }

    /** one roster row per enabled student — computed, never stored */
    record AssignmentRosterRow(
            UUID learnerId,
            String displayName,
            /** "complete" | "late" | "missing" (the hub's roster vocabulary) */
            String state,
            Instant submittedAt,
            Integer score,
            Integer questionsCompleted) {
    }

    record AssignmentRosterView(AssignmentView assignment, List<AssignmentRosterRow> rows) {
    }

    /** the learner's own hand-in (latest evidence row) */
    record AssignmentSubmissionView(
            int questionsCompleted,
            Integer score,
            Instant submittedAt) {

        public static AssignmentSubmissionView from(AssignmentSubmission s) {
            return new AssignmentSubmissionView(s.questionsCompleted(), s.score(), s.occurredAt());
        }
    }

    record LearnerAssignmentView(AssignmentView assignment, AssignmentSubmissionView mySubmission) {
    }
}
