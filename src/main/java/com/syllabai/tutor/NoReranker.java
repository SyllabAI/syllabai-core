package com.syllabai.tutor;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default reranker Strategy (T-024): deterministic identity — the fused order
 * IS the reranked order, and {@code rerankScore} copies the fused score so
 * downstream consumers never see null. A cross-encoder or external reranker
 * replaces this bean later without touching {@code KaRagService} (Master Spec
 * §23 Strategy pattern).
 */
@Component
public class NoReranker implements EvidenceReranker {

    @Override
    public List<EvidenceItem> rerank(String query, List<EvidenceItem> candidates) {
        return candidates.stream()
                .map(item -> item.withRerankScore(item.fusedScore()))
                .toList();
    }
}
