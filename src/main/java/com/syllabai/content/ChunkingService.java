package com.syllabai.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deterministic chunking of canonical documents (T-013, §9 "retrieval index" stage).
 *
 * <p>Rules — all deterministic, no model access, no clocks, no randomness:
 * <ul>
 *   <li>Text source: {@code textBlocks} + {@code tables} + {@code equations}
 *       (flattened text), ordered by {@code (page_number, reading_order)} — the
 *       canonical reading order. Figures carry no text and are skipped.</li>
 *   <li>Block boundaries are respected: a chunk is always a whole number of blocks.
 *       A chunk closes when adding the next block would exceed {@code targetTokens};
 *       a single block larger than {@code maxTokens} becomes its own (oversized)
 *       chunk rather than being silently truncated or split.</li>
 *   <li>Estimate: {@code ceil(chars / 4)} tokens — a documented, stable proxy, not a
 *       provider tokenizer; reproducibility beats precision here.</li>
 *   <li>Every chunk keeps its element ids in reading order — the §8/§17 provenance
 *       spine retrieval results cite back through.</li>
 * </ul>
 */
@Service
public class ChunkingService {

    private final int targetTokens;
    private final int maxTokens;

    public ChunkingService(
            @Value("${syllabai.content.chunking.target-tokens:300}") int targetTokens,
            @Value("${syllabai.content.chunking.max-tokens:800}") int maxTokens) {
        if (targetTokens < 50 || maxTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "chunking sizes invalid: target=" + targetTokens + " max=" + maxTokens);
        }
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
    }

    public List<ChunkDraft> chunk(CanonicalDocumentDto doc) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        if (doc.textBlocks() != null) {
            for (var e : doc.textBlocks()) {
                if (e != null && e.text() != null && !e.text().isBlank()) {
                    candidates.add(new ChunkCandidate(e.elementId(), e.pageNumber(),
                            e.readingOrder(), e.text().strip()));
                }
            }
        }
        if (doc.tables() != null) {
            for (var e : doc.tables()) {
                if (e != null && e.text() != null && !e.text().isBlank()) {
                    candidates.add(new ChunkCandidate(e.elementId(), e.pageNumber(),
                            e.readingOrder(), e.text().strip()));
                }
            }
        }
        if (doc.equations() != null) {
            for (var e : doc.equations()) {
                String text = e == null ? null
                        : (e.text() != null && !e.text().isBlank() ? e.text()
                        : (e.latex() != null && !e.latex().isBlank() ? e.latex() : null));
                if (text != null) {
                    candidates.add(new ChunkCandidate(e.elementId(), e.pageNumber(),
                            e.readingOrder(), text.strip()));
                }
            }
        }
        // canonical reading order: page, then reading_order; element id breaks ties
        // deterministically when an engine emits equal ordering keys
        candidates.sort(Comparator.comparingInt(ChunkCandidate::pageNumber)
                .thenComparingInt(ChunkCandidate::readingOrder)
                .thenComparing(ChunkCandidate::elementId));

        List<ChunkDraft> chunks = new ArrayList<>();
        List<ChunkCandidate> current = new ArrayList<>();
        int currentTokens = 0;

        for (ChunkCandidate candidate : candidates) {
            int candidateTokens = estimate(candidate.text());
            if (candidateTokens > maxTokens) {
                // oversized block: flush what we have, then emit it as its own chunk
                if (!current.isEmpty()) {
                    chunks.add(build(current, currentTokens));
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
                chunks.add(build(List.of(candidate), candidateTokens));
                continue;
            }
            if (currentTokens + candidateTokens > targetTokens && !current.isEmpty()) {
                chunks.add(build(current, currentTokens));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(candidate);
            currentTokens += candidateTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(build(current, currentTokens));
        }
        return chunks;
    }

    private ChunkDraft build(List<ChunkCandidate> blocks, int tokenEstimate) {
        StringBuilder content = new StringBuilder();
        List<String> elementIds = new ArrayList<>(blocks.size());
        int pageStart = Integer.MAX_VALUE;
        int pageEnd = Integer.MIN_VALUE;
        for (ChunkCandidate b : blocks) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(b.text());
            elementIds.add(b.elementId());
            pageStart = Math.min(pageStart, b.pageNumber());
            pageEnd = Math.max(pageEnd, b.pageNumber());
        }
        return new ChunkDraft(content.toString(), pageStart, pageEnd, elementIds,
                Math.max(1, tokenEstimate));
    }

    /** Deterministic estimate documented in the class javadoc. */
    static int estimate(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }

    private record ChunkCandidate(String elementId, int pageNumber, int readingOrder,
                                  String text) {
    }
}
