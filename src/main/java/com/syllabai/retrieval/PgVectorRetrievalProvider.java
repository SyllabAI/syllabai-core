package com.syllabai.retrieval;

import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.VectorRetriever;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapter lifting the existing {@link VectorRetriever} serving port (the
 * pgvector arm, arm A) into the retrieval fabric (T-C14). The vector side
 * keeps its own cosine floor and honest degradation (empty candidates when no
 * embedding provider is configured) — this adapter maps, it does not re-rank
 * and does not widen the scope.
 */
@Component
public class PgVectorRetrievalProvider implements RetrievalProvider {

    private final VectorRetriever delegate;

    public PgVectorRetrievalProvider(VectorRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return "pgvector";
    }

    /**
     * {@code true}: unavailability is expressed by the delegate's honest empty
     * degradation, not by a health flag that could go stale — the arm keeps
     * mirroring {@code ContentVectorRetriever}'s existing behavior (contract
     * sketch invariant 6).
     */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        if (query.normalizedQuery() == null || query.normalizedQuery().isBlank()) {
            return List.of();
        }
        List<EvidenceItem> items = delegate.retrieve(
                query.normalizedQuery(), query.limit(), query.scope());
        return items.stream()
                .map(this::toCandidate)
                .toList();
    }

    private RetrievalCandidate toCandidate(EvidenceItem item) {
        return new RetrievalCandidate(
                id(),
                item.documentRowId(),
                item.documentId(),
                item.documentVersion(),
                item.chunkId() == null ? null : String.valueOf(item.chunkId()),
                item.nodeId(),
                item.nodeCode(),
                item.content(),
                item.retrievalScore(),
                null,
                null,
                java.util.Map.of());
    }
}
