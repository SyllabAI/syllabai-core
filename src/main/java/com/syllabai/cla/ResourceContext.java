package com.syllabai.cla;

import com.syllabai.knowledge.KnowledgeNode;
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
 * @param reference         the resolved topic node id (canonical anchor)
 * @param rootId            the subject KG root the request was scoped to
 * @param subjectCode       owning subject code (isolation + provenance)
 * @param topicCode         canonical KG code, e.g. IALCHEM2018-U1-T3
 * @param topicTitle        canonical topic title
 * @param curriculumVersion curriculum identity RESOLVED from the anchor —
 *                          never client-supplied
 * @param validationState   resolved server-side from the curriculum store
 * @param learnerId         provenance: the learner this request belongs to
 * @param resolvedAt        provenance: resolution time
 */
public record ResourceContext(
        Kind kind,
        UUID reference,
        UUID rootId,
        String subjectCode,
        String topicCode,
        String topicTitle,
        CurriculumVersionInfo curriculumVersion,
        KnowledgeNode.ValidationStatus validationState,
        UUID learnerId,
        Instant resolvedAt) {

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
}
