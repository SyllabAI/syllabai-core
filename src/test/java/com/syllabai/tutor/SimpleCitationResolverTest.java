package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Citation resolution (T-024): one citation per evidence item, labels name the
 * concrete source (kind + page / topic code), deep links resolve into the
 * content and KG APIs.
 */
class SimpleCitationResolverTest {

    private final SimpleCitationResolver resolver = new SimpleCitationResolver();

    @Test
    @DisplayName("chunk evidence cites kind + page and links into the content API")
    void chunkCitations() {
        UUID docRow = UUID.randomUUID();
        EvidenceItem ms = EvidenceItem.fromChunk(docRow, "ms-1", 2, UUID.randomUUID(), 4,
                "MARK_SCHEME", "accept 'shapes determined by electron pair repulsion'",
                6, 6, List.of("e10"), "gemini", 0.81);
        EvidenceItem qp = EvidenceItem.fromChunk(UUID.randomUUID(), "qp-1", 1,
                UUID.randomUUID(), 0, "QUESTION_PAPER", "question text", 2, 3, List.of(),
                "gemini", 0.6);

        List<CitationResolver.Citation> citations = resolver.resolve(List.of(ms, qp));

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).index()).isEqualTo(1);
        assertThat(citations.get(0).label()).isEqualTo("Mark scheme — p6");
        assertThat(citations.get(0).sourceType()).isEqualTo("MARK_SCHEME");
        assertThat(citations.get(0).documentId()).isEqualTo("ms-1");
        assertThat(citations.get(0).deepLink())
                .isEqualTo("/api/v1/teacher/content/documents/" + docRow + "?page=6");

        assertThat(citations.get(1).label()).isEqualTo("Question paper — pp2–3");
        assertThat(citations.get(1).page()).isEqualTo(2);
    }

    @Test
    @DisplayName("KG evidence cites the spec topic and links into the KG API")
    void knowledgeNodeCitations() {
        UUID nodeId = UUID.randomUUID();
        EvidenceItem topic = EvidenceItem.fromNode(nodeId, "IALCHEM2018-U1-T3", "TOPIC",
                "Bonding and Structure", null, 0.5);

        List<CitationResolver.Citation> citations = resolver.resolve(List.of(topic));

        assertThat(citations.get(0).label())
                .isEqualTo("Specification topic IALCHEM2018-U1-T3 — Bonding and Structure");
        assertThat(citations.get(0).sourceType()).isEqualTo("KNOWLEDGE_NODE");
        assertThat(citations.get(0).nodeId()).isEqualTo(nodeId);
        assertThat(citations.get(0).deepLink()).isEqualTo("/api/v1/knowledge/nodes/" + nodeId);
    }

    @Test
    @DisplayName("missing pages degrade to label-only citations, never 'null'")
    void missingPages() {
        EvidenceItem noPage = EvidenceItem.fromChunk(UUID.randomUUID(), "d", 1,
                UUID.randomUUID(), 0, "SYLLABUS", "content", null, null, List.of(), "m", 0.5);
        CitationResolver.Citation citation = resolver.resolve(List.of(noPage)).get(0);

        assertThat(citation.label()).isEqualTo("Specification");
        assertThat(citation.page()).isNull();
        assertThat(citation.deepLink()).isEqualTo("/api/v1/teacher/content/documents/"
                + noPage.documentRowId());
    }
}
