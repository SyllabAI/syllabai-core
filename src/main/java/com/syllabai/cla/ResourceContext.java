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
        Boolean attempted) {

    /**
     * Closed enum (contract §1) — SPECIFICATION_POINT | KG_TOPIC |
     * NOTE_SECTION | QUESTION_PART | SMART_LESSON | PAST_PAPER_QUESTION,
     * extensible by decision only. Step 1 RESOLVES only KG_TOPIC; the other
     * values name the contract's closed set so extensions are explicit.
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

    /** question-anchored context predicate (§7 gate input shaping) */
    public boolean isQuestionContext() {
        return kind == Kind.PAST_PAPER_QUESTION;
    }
}
