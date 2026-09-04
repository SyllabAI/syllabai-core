package com.syllabai.tutor;

import java.util.List;
import java.util.UUID;

/**
 * A retrieval candidate that survived fusion — the unit of KA-RAG evidence
 * (Master Spec §13, T-024). The tutor operates on <em>evidence</em>, never on
 * raw strings: every item carries document grounding (canonical chunk) or KG
 * grounding (curriculum node), page/element provenance, associated topics and
 * its scores through the pipeline (retrieval → fused → reranked). Downstream
 * citations, research traceability and explanation auditing all read from
 * here (§17).
 *
 * @param source           where this evidence came from
 * @param content          the citable text (chunk content / node title + description)
 * @param documentRowId    documents.id row (null for KNOWLEDGE_NODE)
 * @param documentId       parser-issued canonical document id (null for KG nodes)
 * @param documentVersion  canonical document version (null for KG nodes)
 * @param chunkId          document_chunks.id (null for KG nodes)
 * @param chunkIndex       deterministic chunk order within the document (null for KG)
 * @param nodeId           knowledge_nodes.id (null for chunks)
 * @param nodeCode         KG code, e.g. IALCHEM2018-U1-T3 (null for chunks)
 * @param nodeType         UNIT/TOPIC/SUBTOPIC (null for chunks)
 * @param nodeTitle        node title (null for chunks)
 * @param pageStart        1-based source page (null when unknown)
 * @param pageEnd          inclusive end page (null when single-page/unknown)
 * @param elementIds       canonical elements backing this evidence (§17)
 * @param topicIds         KG topics this evidence is associated with (v0: the
 *                         intent-matched topics; for KG nodes the node itself)
 * @param retrievalScore   raw retrieval score (cosine for chunks, match
 *                         specificity for KG nodes — NOT comparable across sources)
 * @param fusedScore       reciprocal-rank-fusion score (comparable; rank basis)
 * @param rerankScore      post-rerank score (null until a reranker runs;
 *                         NoReranker copies the fused score)
 */
public record EvidenceItem(
        EvidenceSource source,
        String content,
        UUID documentRowId,
        String documentId,
        Integer documentVersion,
        UUID chunkId,
        Integer chunkIndex,
        UUID nodeId,
        String nodeCode,
        String nodeType,
        String nodeTitle,
        Integer pageStart,
        Integer pageEnd,
        List<String> elementIds,
        List<UUID> topicIds,
        double retrievalScore,
        double fusedScore,
        Double rerankScore) {

    public enum EvidenceSource {
        /** canonical mark-scheme chunk */
        MARK_SCHEME,
        /** canonical question-paper chunk */
        QUESTION_PAPER,
        /** canonical syllabus/specification chunk */
        SYLLABUS,
        /** other canonical document chunk */
        OTHER,
        /** a matched KG curriculum node (unit/topic/subtopic) */
        KNOWLEDGE_NODE
    }

    /** Build evidence from a vector chunk hit (retrievalScore = cosine similarity). */
    public static EvidenceItem fromChunk(UUID documentRowId, String documentId,
                                         int documentVersion, UUID chunkId, int chunkIndex,
                                         String kind, String content, Integer pageStart,
                                         Integer pageEnd, List<String> elementIds,
                                         String embeddingModel, double cosine) {
        EvidenceSource source = switch (kind == null ? "OTHER" : kind) {
            case "MARK_SCHEME" -> EvidenceSource.MARK_SCHEME;
            case "QUESTION_PAPER" -> EvidenceSource.QUESTION_PAPER;
            case "SYLLABUS" -> EvidenceSource.SYLLABUS;
            default -> EvidenceSource.OTHER;
        };
        return new EvidenceItem(source, content, documentRowId, documentId, documentVersion,
                chunkId, chunkIndex, null, null, null, null, pageStart, pageEnd,
                elementIds == null ? List.of() : List.copyOf(elementIds), List.of(),
                cosine, 0.0, null);
    }

    /** Build evidence from a matched KG structure node (retrievalScore = match specificity). */
    public static EvidenceItem fromNode(UUID nodeId, String code, String nodeType,
                                        String title, String description, double matchScore) {
        String content = (title == null ? "" : title)
                + (description == null || description.isBlank() ? "" : " — " + description);
        return new EvidenceItem(EvidenceSource.KNOWLEDGE_NODE, content, null, null, null,
                null, null, nodeId, code, nodeType, title, null, null, List.of(),
                List.of(nodeId), matchScore, 0.0, null);
    }

    /** v0 semantics: chunk evidence is associated with the intent-matched topics. */
    public EvidenceItem withTopicIds(List<UUID> topics) {
        return new EvidenceItem(source, content, documentRowId, documentId, documentVersion,
                chunkId, chunkIndex, nodeId, nodeCode, nodeType, nodeTitle, pageStart, pageEnd,
                elementIds, topics == null ? List.of() : List.copyOf(topics), retrievalScore,
                fusedScore, rerankScore);
    }

    /** Fused position (used by the fusion stage; keeps the retrieval score). */
    public EvidenceItem withFusedScore(double fused) {
        return new EvidenceItem(source, content, documentRowId, documentId, documentVersion,
                chunkId, chunkIndex, nodeId, nodeCode, nodeType, nodeTitle, pageStart, pageEnd,
                elementIds, topicIds, retrievalScore, fused, rerankScore);
    }

    /** Reranked position (used by the reranker stage). */
    public EvidenceItem withRerankScore(double rerank) {
        return new EvidenceItem(source, content, documentRowId, documentId, documentVersion,
                chunkId, chunkIndex, nodeId, nodeCode, nodeType, nodeTitle, pageStart, pageEnd,
                elementIds, topicIds, retrievalScore, fusedScore, rerank);
    }
}
