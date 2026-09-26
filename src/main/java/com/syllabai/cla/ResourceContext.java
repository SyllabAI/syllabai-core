package com.syllabai.cla;

import java.time.Instant;
import java.util.UUID;

/**
 * ResourceContext (CLA contract §1): the server-resolved anchor of "what the
 * learner is looking at". The client NEVER asserts context semantics — it
 * passes opaque references (subject root + topic node id in step 1) and the
 * server resolves them fail-closed ({@link ClaContextResolver}).
 *
 * <p>Binding rules (contract §1): resolution is server-side and fail-closed
 * (unresolvable = 4xx, never a best-effort guess); the validation state is
 * resolved server-side and gates evidence assembly; contexts are
 * per-request and stateless — the CLA keeps no server-side hidden session
 * memory.</p>
 *
 * @param kind              closed context-kind enum (extensible by decision only)
 * @param reference         the resolved anchor id (KG topic node id, or the
 *                          question id for PAST_PAPER_QUESTION)
 * @param topicNodeId       the KG topic the context is anchored to (== reference
 *                          for KG_TOPIC; the question's primary topic for
 *                          PAST_PAPER_QUESTION) — the deterministic spec anchor
 * @param rootId            the subject KG root the request was scoped to
 * @param subjectCode       owning subject code (isolation + provenance)
 * @param topicCode         canonical KG code of the anchor topic
 * @param topicTitle        canonical title of the anchor topic
 * @param curriculumVersion curriculum identity RESOLVED from the anchor —
 *                          never client-supplied
 * @param validationState   resolved server-side from the curriculum/question store
 *                          (name of the store's own validation enum — VALIDATED only
 *                          passes the gate)
 * @param learnerId         provenance: the learner this request belongs to
 * @param resolvedAt        provenance: resolution time
 * @param questionStem      question-anchored contexts: the served stem (null on
 *                          KG_TOPIC) — the learner is already looking at it
 * @param questionCommandWord  question-anchored contexts: command word (nullable)
 * @param questionMarks     question-anchored contexts: total marks (0 on KG_TOPIC)
 * @param paperCode         question-anchored contexts: exam paper code (nullable)
 * @param attempted         question-anchored contexts: DETERMINISTIC attempt-state
 *                          read (the §7.3/§7.4 gate input — attempt history for
 *                          this learner and question, the same substrate as
 *                          Review Hub); null on KG_TOPIC
 * @param partLabel         QUESTION_PART contexts: the anchored part's label
 *                          ("a", "b-ii", …) on the question's CURRENT validated
 *                          version; null on every other kind
 * @param lessonAction      SMART_LESSON contexts: the learner's OWN deterministic
 *                          Smart Lesson decision for the anchored topic (the
 *                          existing smart-lesson/v2 ladder — action type, reason
 *                          code, redirect target, honest reason detail). Relevant
 *                          learner state for FRAMING (contract §2.3), never a
 *                          source of educational truth; null on every other kind
 * @param noteId            NOTE_SECTION contexts: the revision note's stable
 *                          business id (the corpus package id, e.g.
 *                          "rn_2VnK66PqbvFKdKYt") — the opaque reference the
 *                          client passed and the server resolved; null on every
 *                          other kind
 * @param noteTitle         NOTE_SECTION contexts: the note's canonical title
 *                          (provenance for the note-lead evidence); null on
 *                          every other kind
 */
public record ResourceContext(
        Kind kind,
        UUID reference,
        UUID topicNodeId,
        UUID rootId,
        String subjectCode,
        String topicCode,
        String topicTitle,
        CurriculumVersionInfo curriculumVersion,
        String validationState,
        UUID learnerId,
        Instant resolvedAt,
        String questionStem,
        String questionCommandWord,
        int questionMarks,
        String paperCode,
        Boolean attempted,
        String partLabel,
        LessonActionInfo lessonAction,
        String noteId,
        String noteTitle) {

    /**
     * The learner's OWN deterministic Smart Lesson decision carried on a
     * SMART_LESSON context (computed by the existing smart-lesson/v2 ladder
     * over the SAME learner model the Smart Lesson surface consumes — no new
     * learner state, no LLM, no probabilities beyond what the ladder already
     * publishes honestly). Strings for the action/reason enums keep the CLA
     * decoupled from the learner DTO's enum identity, mirroring how
     * {@code partLabel} carries the assessment label.
     *
     * @param actionType     the ladder's chosen ActionType name (e.g.
     *                       PRACTISE_QUESTIONS, REVIEW_TOPIC, REMEDIATE_PREREQUISITE)
     * @param reasonCode     the ladder's audit ReasonCode name (e.g.
     *                       INSUFFICIENT_COVERAGE, DUE_REVIEW)
     * @param targetNodeId   the action's target node (may be a prerequisite or
     *                       corrective concept the ladder honestly redirects to;
     *                       nullable)
     * @param targetCode     the target's canonical KG code (nullable)
     * @param targetTitle    the target's canonical title (nullable)
     * @param reasonDetail   the ladder's own human-readable, auditable reason
     * @param servableQuestionCount   servable questions on the action's target topic
     */
    public record LessonActionInfo(String actionType,
                                   String reasonCode,
                                   UUID targetNodeId,
                                   String targetCode,
                                   String targetTitle,
                                   String reasonDetail,
                                   int servableQuestionCount) {
    }

    /**
     * Closed enum (contract §1) — SPECIFICATION_POINT | KG_TOPIC |
     * NOTE_SECTION | QUESTION_PART | SMART_LESSON | PAST_PAPER_QUESTION,
     * extensible by decision only. Runtime serves KG_TOPIC,
     * SPECIFICATION_POINT, PAST_PAPER_QUESTION, QUESTION_PART,
     * SMART_LESSON and NOTE_SECTION (the revision-note reader's anchor:
     * the note the learner is reading, resolved through its spec-point
     * codes to a VALIDATED curriculum node).
     */
    public enum Kind {
        SPECIFICATION_POINT,
        KG_TOPIC,
        NOTE_SECTION,
        QUESTION_PART,
        SMART_LESSON,
        PAST_PAPER_QUESTION
    }

    /**
     * Curriculum identity resolved from the owning subject (contract §1:
     * curriculumVersion resolved from the referenced anchor).
     */
    public record CurriculumVersionInfo(String code, String board, String qualification,
                                        String status) {
    }

    /**
     * question-anchored context predicate (§7 gate input shaping): both
     * question-level (PAST_PAPER_QUESTION) and part-level (QUESTION_PART)
     * anchors are assessment content — the leakage gate treats them
     * identically (CHECK needs attempt evidence; mark-scheme DOCUMENT chunks
     * never serve on assessment anchors).
     */
    public boolean isQuestionContext() {
        return kind == Kind.PAST_PAPER_QUESTION || kind == Kind.QUESTION_PART;
    }

    /** part-level anchor predicate (part-scoped scheme evidence selection) */
    public boolean isQuestionPartContext() {
        return kind == Kind.QUESTION_PART;
    }

    /**
     * topic-anchored (non-assessment) predicate: KG_TOPIC, SMART_LESSON and
     * NOTE_SECTION share the identical curriculum spine and the identical
     * tutor-parity evidence rules — a Smart Lesson and a revision note are
     * topic-anchored learning contexts, NOT assessment content (no §7 leakage
     * boundary of their own, no invented marking semantics). A note context
     * additionally LEADS with the note's own sections as deterministic
     * evidence (what the learner is looking at), exactly like a question
     * context leads with its stem.
     */
    public boolean isTopicContext() {
        return kind == Kind.KG_TOPIC || kind == Kind.SMART_LESSON
                || kind == Kind.NOTE_SECTION;
    }

    /** note-anchored context predicate (the note-lead evidence branch) */
    public boolean isNoteContext() {
        return kind == Kind.NOTE_SECTION;
    }

    /**
     * Copy of this context with the lesson action attached (resolver-internal:
     * the base spine resolves first, then the deterministic lesson decision
     * enriches it without re-running the gates).
     */
    public ResourceContext withLessonAction(LessonActionInfo lessonAction) {
        return new ResourceContext(kind, reference, topicNodeId, rootId, subjectCode,
                topicCode, topicTitle, curriculumVersion, validationState, learnerId,
                resolvedAt, questionStem, questionCommandWord, questionMarks,
                paperCode, attempted, partLabel, lessonAction, noteId, noteTitle);
    }

    /**
     * Copy of this context with the note identity attached (resolver-internal:
     * the curriculum spine resolves first — fail-closed through the spec-point
     * gate — then the note's own identity enriches it for the answer view and
     * the note-lead evidence).
     */
    public ResourceContext withNote(String noteId, String noteTitle) {
        return new ResourceContext(kind, reference, topicNodeId, rootId, subjectCode,
                topicCode, topicTitle, curriculumVersion, validationState, learnerId,
                resolvedAt, questionStem, questionCommandWord, questionMarks,
                paperCode, attempted, partLabel, lessonAction, noteId, noteTitle);
    }
}
