package com.syllabai.cla.dto;

import com.syllabai.cla.ClaToolRegistry;
import com.syllabai.cla.ResourceContext;
import com.syllabai.cla.ResponseMode;
import com.syllabai.tutor.CitationResolver;
import java.util.List;
import java.util.UUID;

/**
 * CLA contextual answer (step-1 view). Shape mirrors the Tutor answer where
 * consumers overlap (answer, citations, evidence strength, refusal flag,
 * model/provider/latency) and adds the context identity the free Tutor does
 * not have: the resolved ResourceContext summary (kind, topic, subject,
 * curriculum version), the explicit response mode, and the read-only tool
 * trace (audit — tool names, argument references, result sizes, latencies;
 * no tool output text is echoed back to the client).
 */
public record ClaAnswerView(
        String answer,
        List<CitationResolver.Citation> citations,
        ContextView context,
        List<TopicAnchorView> topics,
        int evidenceCount,
        String model,
        String provider,
        boolean refused,
        double latencyMs,
        List<ToolTraceView> tools) {

    /** the resolved context, exactly as the server resolved it (never client-asserted) */
    public record ContextView(
            String kind,
            UUID reference,
            UUID topicNodeId,
            UUID rootId,
            String subjectCode,
            String topicCode,
            String topicTitle,
            String curriculumVersion,
            String curriculumBoard,
            String curriculumQualification,
            String validationState,
            ResponseMode mode,
            String questionStem,
            String questionCommandWord,
            int questionMarks,
            String paperCode,
            Boolean attempted,
            String partLabel,
            LessonActionView lessonAction,
            String noteId,
            String noteTitle) {

        public static ContextView of(ResourceContext context, ResponseMode mode) {
            return new ContextView(
                    context.kind().name(), context.reference(), context.topicNodeId(),
                    context.rootId(),
                    context.subjectCode(), context.topicCode(), context.topicTitle(),
                    context.curriculumVersion().code(), context.curriculumVersion().board(),
                    context.curriculumVersion().qualification(),
                    context.validationState(), mode,
                    context.questionStem(), context.questionCommandWord(),
                    context.questionMarks(), context.paperCode(), context.attempted(),
                    context.partLabel(), lessonActionOf(context.lessonAction()),
                    context.noteId(), context.noteTitle());
        }

        private static LessonActionView lessonActionOf(ResourceContext.LessonActionInfo a) {
            return a == null ? null : new LessonActionView(a.actionType(), a.reasonCode(),
                    a.targetNodeId(), a.targetCode(), a.targetTitle(), a.reasonDetail(),
                    a.servableQuestionCount());
        }
    }

    /**
     * SMART_LESSON contexts: the learner's OWN deterministic Smart Lesson
     * next action (the smart-lesson/v2 ladder's honest decision — same shape
     * the Smart Lesson surface renders; framing/provenance only, never a
     * source of educational truth). Null on every other kind.
     */
    public record LessonActionView(String actionType,
                                   String reasonCode,
                                   java.util.UUID targetNodeId,
                                   String targetCode,
                                   String targetTitle,
                                   String reasonDetail,
                                   int servableQuestionCount) {
    }

    /** the deterministic topic anchor(s) — exactly the resolved context in step 1 */
    public record TopicAnchorView(String code, String title, double matchScore) {
    }

    /** audit trace of one read-only tool invocation (contract §4.4) */
    public record ToolTraceView(String tool, String args, int resultSize, long latencyMs) {

        static ToolTraceView of(ClaToolRegistry.ToolTrace t) {
            return new ToolTraceView(t.tool(), t.args(), t.resultSize(), t.latencyMs());
        }
    }

    public static ClaAnswerView of(String answer,
                                   List<CitationResolver.Citation> citations,
                                   ResourceContext context,
                                   ResponseMode mode,
                                   int evidenceCount,
                                   String model,
                                   String provider,
                                   boolean refused,
                                   double latencyMs,
                                   List<ClaToolRegistry.ToolTrace> toolTraces) {
        return new ClaAnswerView(
                answer,
                List.copyOf(citations),
                ContextView.of(context, mode),
                List.of(new TopicAnchorView(context.topicCode(), context.topicTitle(), 1.0)),
                evidenceCount, model, provider, refused, latencyMs,
                toolTraces.stream().map(ToolTraceView::of).toList());
    }
}
