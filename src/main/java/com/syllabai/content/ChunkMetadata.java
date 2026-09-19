package com.syllabai.content;

import java.util.List;
import java.util.UUID;

/**
 * Write-time identity mirror for a chunk (V33, Embedding v2 plan §6.3) — the
 * metadata columns every serving/enumeration predicate filters on. Built once
 * per document by {@link ContentIngestionService} from the doc-level
 * {@code retrieval} block and stamped onto every chunk of that document.
 *
 * @param kind       denormalized {@link Document} kind (immutable after insert)
 * @param subjectId  resolved subjects.id (from retrieval.subjectCode); null only
 *                   when the document carries no resolvable subject — such chunks
 *                   are never served (fail-closed, same posture as T-C07)
 * @param series     canonical session enum JAN/JUN/NOV (validated upstream)
 * @param year       exam year
 * @param paperCode  paper code (e.g. "1C"); null for notes/spec/textbook
 * @param atomNumber question-atom number from the chunk's group key; null for
 *                   non-paper kinds and legacy-shaped documents
 * @param specCodes  spec-point codes the chunk is anchored to
 * @param embedRev   corpus-generation identity ({@link ChunkVectorRepository#CURRENT_EMBED_REV})
 */
public record ChunkMetadata(Document.Kind kind, UUID subjectId, String series, Integer year,
                            String paperCode, String atomNumber, List<String> specCodes,
                            int embedRev) {
}
