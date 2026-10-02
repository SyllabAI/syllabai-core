package com.syllabai.content;

import java.util.List;
import java.util.UUID;

/**
 * A retrieval hit over the chunk index (§13). Score is cosine similarity
 * (1 − cosine distance) in [-1, 1]; element ids + page range carry the citation
 * provenance back to the canonical document (§8/§17).
 *
 * <p>{@code docVersion} is the owning document's canonical version, joined
 * from {@code documents.doc_version} by the search SQL itself (both the vector
 * and the lexical repository inner-join {@code documents}, where the column is
 * {@code INT NOT NULL}) — a hit always carries its version, so callers never
 * re-read the document row per hit (the T-C32-class N+1 this field kills:
 * version resolution was {@code documents.findById()} per hit on the serving
 * path).</p>
 */
public record ChunkHit(UUID chunkId, UUID documentRowId, int docVersion, String documentId, String kind,
                       int chunkIndex, String content, Integer pageStart, Integer pageEnd,
                       List<String> elementIds, String embeddingModel, double score) {
}
