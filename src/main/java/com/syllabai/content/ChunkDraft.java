package com.syllabai.content;

import java.util.List;

/**
 * A chunk produced by {@link ChunkingService} before persistence — a pure value,
 * deterministic for a given canonical document.
 *
 * @param content      the text of the chunk (blocks joined with newlines)
 * @param pageStart    first page the chunk draws from (null only for empty documents)
 * @param pageEnd      last page the chunk draws from
 * @param elementIds   canonical element ids in reading order — the citation/provenance
 *                     spine (§8/§17): every retrieved chunk maps back to exact elements
 * @param tokenEstimate deterministic size estimate (ceil(chars / 4)); NOT a provider
 *                     tokenizer — documented and stable so chunking is reproducible
 *                     without model access
 */
public record ChunkDraft(String content, Integer pageStart, Integer pageEnd,
                         List<String> elementIds, int tokenEstimate) {
}
