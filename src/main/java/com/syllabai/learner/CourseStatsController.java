package com.syllabai.learner;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.learner.dto.CourseStatsView;
import com.syllabai.revisionnotes.RevisionNoteViewedRepository;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course-stats read model (ADR-029 tranche 4.10 — the "course-stats" core
 * contract): GET /api/v1/learners/me/course-stats. One request answers
 * "how much of this course have I done on my account?" with coverage
 * aggregates computed server-side over the FULL evidence tables — the
 * windowed read models (latest-50 rating/vote events, latest-100 attempt
 * history) and the per-node skill sums cannot answer it honestly.
 *
 * <p>Aggregates three evidence classes the learner already writes through
 * their own endpoints: attempts (assessment), revision-note views
 * (revision-notes) and flashcard ratings (learner). Cross-module repository
 * injection follows the class-analytics precedent (teacher class
 * intelligence reads AttemptRepository); the /api/v1/learners/me route
 * convention keeps the resource owned by the authenticated learner —
 * identity from the JWT, never a parameter.</p>
 *
 * <p>Honesty contract (see {@link CourseStatsView} for the full statement):
 * read-only, zero derivation, zero writes — coverage is exposure, never
 * mastery, and nothing here touches SkillState / BKT / misconception /
 * review state. Deliberately NOT in the R8 LLM tier (zero LLM cost).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class CourseStatsController {

    private final AttemptRepository attempts;
    private final FlashcardRatingRepository flashcardRatings;
    private final RevisionNoteViewedRepository noteViews;

    public CourseStatsController(AttemptRepository attempts,
                                 FlashcardRatingRepository flashcardRatings,
                                 RevisionNoteViewedRepository noteViews) {
        this.attempts = attempts;
        this.flashcardRatings = flashcardRatings;
        this.noteViews = noteViews;
    }

    @GetMapping("/course-stats")
    public CourseStatsView courseStats(@CurrentUserId UUID learnerId) {
        return new CourseStatsView(
                learnerId,
                (int) attempts.countByLearnerId(learnerId),
                (int) attempts.countDistinctQuestionsByLearnerId(learnerId),
                (int) noteViews.countByUserId(learnerId),
                (int) flashcardRatings.countDistinctCardsByLearnerId(learnerId));
    }
}
