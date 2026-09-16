package com.syllabai.retrieval;

import com.syllabai.tutor.KnowledgeRetriever;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapter lifting the existing {@link KnowledgeRetriever} serving port (the
 * authoritative KG arm) into the retrieval fabric (T-C14). Only matched topics
 * become candidates; the pedagogical context they carry (prerequisites,
 * misconceptions) is <em>not</em> a candidate-generation output — it is
 * assembled downstream by the tutor pipeline and never mutated by anything a
 * provider returned (educational-truth invariant).
 */
@Component
public class AuthoritativeKgRetrievalProvider implements RetrievalProvider {

    private final KnowledgeRetriever delegate;

    public AuthoritativeKgRetrievalProvider(KnowledgeRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return "authoritative-kg";
    }

    /**
     * {@code true}: the KG is the authoritative substrate — when intent
     * resolution matches nothing the provider returns an empty candidate list,
     * which is a distinct, honest state from provider-down.
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
        KnowledgeRetriever.KnowledgeContext context = delegate.retrieve(
                query.normalizedQuery(), query.limit(), query.scope());
        return context.topics().stream()
                .map(this::toCandidate)
                .toList();
    }

    private RetrievalCandidate toCandidate(KnowledgeRetriever.KnowledgeContext.MatchedTopic topic) {
        return new RetrievalCandidate(
                id(),
                null,
                null,
                null,
                String.valueOf(topic.nodeId()),
                topic.nodeId(),
                topic.code(),
                topic.title(),
                topic.matchScore(),
                null,
                null,
                java.util.Map.of());
    }
}
