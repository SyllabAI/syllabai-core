package com.syllabai.content;

import java.util.List;
import java.util.UUID;

/**
 * A retrieval hit over the chunk index (§13). Score is cosine similarity
 * (1 − cosine distance) in [-1, 1]; element ids + page range carry the citation
 * provenance back to the canonical document (§8/§17).
 */
public record ChunkHit(UUID chunkId, UUID documentRowId, String documentId, String kind,
                       int chunkIndex, String content, Integer pageStart, Integer pageEnd,
                       List<String> elementIds, String embeddingModel, double score) {
}
