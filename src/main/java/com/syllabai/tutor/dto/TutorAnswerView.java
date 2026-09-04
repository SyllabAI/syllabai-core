package com.syllabai.tutor.dto;

import com.syllabai.tutor.CitationResolver;
import java.util.List;

/**
 * The KA-RAG answer view (T-024): grounded answer, verbatim citations with
 * deep links, matched curriculum topics, and full traceability fields
 * (model/provider, refusal flag, latency) for the research record.
 *
 * @param answer      the tutor's answer ([n] markers reference citations)
 * @param citations   one per evidence item, 1-based
 * @param topics      intent-matched curriculum topics (deterministic match)
 * @param evidenceCount how many evidence items ground the answer
 * @param model       model identity (null on deterministic refusal)
 * @param provider    provider identity ("deterministic-refusal" when refused)
 * @param refused     true when no evidence survived retrieval — answer is a refusal
 * @param latencyMs   pipeline latency in milliseconds
 */
public record TutorAnswerView(
        String answer,
        List<CitationResolver.Citation> citations,
        List<TopicMatch> topics,
        int evidenceCount,
        String model,
        String provider,
        boolean refused,
        double latencyMs) {

    public static TutorAnswerView of(String answer,
                                     List<CitationResolver.Citation> citations,
                                     List<TopicMatch> topics,
                                     int evidenceCount, String model, String provider,
                                     boolean refused, double latencyMs) {
        return new TutorAnswerView(answer, List.copyOf(citations), List.copyOf(topics),
                evidenceCount, model, provider, refused, latencyMs);
    }

    /**
     * @param code        KG topic code, e.g. IALCHEM2018-U1-T1
     * @param title       topic title
     * @param matchScore  deterministic match specificity 0..1
     */
    public record TopicMatch(String code, String title, double matchScore) {
    }
}
