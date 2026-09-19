package com.syllabai.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
 *   <li><em>Atom-boundary mode (Embedding v2, plan §4.1)</em>: when the document's
 *       blocks carry {@code group_key} (atom identity), a group-key change is a
 *       HARD chunk boundary — the current chunk is flushed exactly like the
 *       oversized-block path. No chunk ever crosses an atom; a question split
 *       across chunks is a citation integrity bug, not a nuance. Token packing
 *       continues WITHIN a group: an atom larger than {@code maxTokens} splits
 *       at block boundaries inside the atom, never across atoms. Documents
 *       without group keys chunk exactly as before (page-marker/reading-order
 *       packing remains the fallback for legacy-shaped documents).</li>
 *   <li>Block boundaries are respected: a chunk is always a whole number of blocks.
 *       A chunk closes when adding the next block would exceed {@code targetTokens};
 *       a single block larger than {@code maxTokens} becomes its own (oversized)
 *       chunk rather than being silently truncated or split.</li>
 *   <li>Estimate: {@code ceil(chars / 4)} tokens — a documented, stable proxy, not a
 *       provider tokenizer; reproducibility beats precision here.</li>
 *   <li>Every chunk keeps its element ids in reading order — the §8/§17 provenance
 *       spine retrieval results cite back through.</li>
 *   <li><em>Per-chunk header projection (plan §4.1 step 4)</em>: every chunk is
 *       stamped with the header line built by {@link ChunkHeaderBuilder} from the
 *       document identity + group key + the chunk's page range, prepended to the
 *       chunk text before embedding. The global 300/800 sizes stay kind-agnostic:
 *       they are compatible with every matrix row's max (QP/MS 900, notes 800,
 *       spec 300 — see the retrieval plan §4.2).</li>
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
        return chunk(doc, null);
    }

    /**
     * @param kind the persisted document kind — used only for header composition
     *             (e.g. the {@code MS Q3} prefix); pass null for kind-agnostic calls
     */
    public List<ChunkDraft> chunk(CanonicalDocumentDto doc, Document.Kind kind) {
        List<ChunkCandidate> candidates = new ArrayList<>();
        if (doc.textBlocks() != null) {
            for (var e : doc.textBlocks()) {
                if (e != null && e.text() != null && !e.text().isBlank()) {
                    candidates.add(new ChunkCandidate(e.elementId(), e.pageNumber(),
                            e.readingOrder(), e.text().strip(), e.groupKey()));
                }
            }
        }
        if (doc.tables() != null) {
            for (var e : doc.tables()) {
                if (e != null && e.text() != null && !e.text().isBlank()) {
                    candidates.add(new ChunkCandidate(e.elementId(), e.pageNumber(),
                            e.readingOrder(), e.text().strip(), e.groupKey()));
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
                            e.readingOrder(), text.strip(), e.groupKey()));
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
        String currentGroupKey = null;

        for (ChunkCandidate candidate : candidates) {
            int candidateTokens = estimate(candidate.text());
            if (candidateTokens > maxTokens) {
                // oversized block: flush what we have, then emit it as its own chunk
                if (!current.isEmpty()) {
                    chunks.add(build(doc, kind, current, currentTokens, currentGroupKey));
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
                chunks.add(build(doc, kind, List.of(candidate), candidateTokens,
                        candidate.groupKey()));
                continue;
            }
            // atom-boundary mode: a group-key change is a hard boundary (plan §4.1
            // step 2) — flush before packing continues within the new group
            if (!current.isEmpty() && !Objects.equals(currentGroupKey, candidate.groupKey())) {
                chunks.add(build(doc, kind, current, currentTokens, currentGroupKey));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            if (currentTokens + candidateTokens > targetTokens && !current.isEmpty()) {
                chunks.add(build(doc, kind, current, currentTokens, currentGroupKey));
                current = new ArrayList<>();
                currentTokens = 0;
            }
            if (current.isEmpty()) {
                currentGroupKey = candidate.groupKey();
            }
            current.add(candidate);
            currentTokens += candidateTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(build(doc, kind, current, currentTokens, currentGroupKey));
        }
        return chunks;
    }

    private ChunkDraft build(CanonicalDocumentDto doc, Document.Kind kind,
                             List<ChunkCandidate> blocks, int tokenEstimate,
                             String groupKey) {
        String header = ChunkHeaderBuilder.build(kind, doc.retrieval(), groupKey,
                blocks.stream().map(ChunkCandidate::pageNumber).filter(Objects::nonNull)
                        .min(Integer::compareTo).orElse(null),
                blocks.stream().map(ChunkCandidate::pageNumber).filter(Objects::nonNull)
                        .max(Integer::compareTo).orElse(null));
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
        String body = content.toString();
        String withHeader = header.isBlank() ? body : header + "\n" + body;
        return new ChunkDraft(withHeader,
                pageStart == Integer.MAX_VALUE ? null : pageStart,
                pageEnd == Integer.MIN_VALUE ? null : pageEnd,
                elementIds, Math.max(1, tokenEstimate), groupKey);
    }

    /** Deterministic estimate documented in the class javadoc. */
    static int estimate(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }

    private record ChunkCandidate(String elementId, int pageNumber, int readingOrder,
                                  String text, String groupKey) {
    }
}
