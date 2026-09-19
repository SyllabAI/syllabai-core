package com.syllabai.content;

import java.util.List;

/**
 * A chunk produced by {@link ChunkingService} before persistence — a pure value,
 * deterministic for a given canonical document.
 *
 * @param content      the text of the chunk (retrieval header prepended when the
 *                     document carries identity material, then blocks joined
 *                     with newlines)
 * @param pageStart    first page the chunk draws from (null only for empty documents)
 * @param pageEnd      last page the chunk draws from
 * @param elementIds   canonical element ids in reading order — the citation/provenance
 *                     spine (§8/§17): every retrieved chunk maps back to exact elements
 * @param tokenEstimate deterministic size estimate (ceil(chars / 4)); NOT a provider
 *                     tokenizer — documented and stable so chunking is reproducible
 *                     without model access
 * @param groupKey     the atom identity shared by every block of this chunk
 *                     ({@code q3}…; null when the document carries no group keys —
 *                     legacy-shaped documents). A chunk never mixes group keys:
 *                     a group-key change is a hard chunk boundary (plan §4.1).
 */
public record ChunkDraft(String content, Integer pageStart, Integer pageEnd,
                         List<String> elementIds, int tokenEstimate, String groupKey) {
}
