package com.syllabai.assessment.dto;

import java.util.List;
import java.util.UUID;

/**
 * The servable-question taxonomy (session-112, ADR-026 exam-questions browser):
 * the curriculum shape a learner browses exam questions by — sections (the
 * knowledge-graph UNIT parents, e.g. 4CH1-S1 "Principles of chemistry") down
 * to topics (the nodes questions map to, e.g. 4CH1-S1-a "States of matter"),
 * each topic carrying the question counts the browser sidebar and the practice
 * topic picker render.
 *
 * <p>Counts are <em>reachable</em> counts: a question counts under a topic when
 * it would be served by {@code GET /api/v1/questions?topicNodeId=} for that
 * topic — the PRIMARY mapping <em>or</em> any secondary {@code question_topics}
 * mapping — deduped per question. That makes the sidebar number exactly the
 * length of the list a click loads (the invariant that would break if counts
 * counted only primaries). Counts are computed under the same servability
 * boundary as every list path (active + MCQ or validated STRUCTURED current
 * version + paper integrity gate) by {@link com.syllabai.assessment.ServableQuestionService}
 * — the one owner of that rule, so the taxonomy cannot drift from serving.</p>
 *
 * <p>Topics with zero servable questions do not appear: the taxonomy is the
 * shape of what can be practised, not the shape of the syllabus. Sections are
 * code-ordered, topics within a section code-ordered — deterministic like every
 * other read model.</p>
 */
public record QuestionTopicTaxonomyView(List<Section> sections) {

    /** one syllabus section (UNIT node) with its question-bearing topics */
    public record Section(UUID nodeId, String code, String title, List<Topic> topics) {
    }

    /** one browsable topic node with its servable-question census */
    public record Topic(UUID nodeId, String code, String title,
                       int questionCount, int mcqCount, int structuredCount) {
    }
}
