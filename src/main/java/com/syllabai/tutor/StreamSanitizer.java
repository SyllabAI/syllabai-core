package com.syllabai.tutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental output hygiene for streamed tutor answers (tutor SSE tranche).
 *
 * <p>The blocking path sanitizes the COMPLETE answer with
 * {@link GroundedTutorGenerator#sanitizeAnswer} — echoed fence markers and
 * out-of-range citation markers are stripped before anything reaches the
 * learner. A token stream cannot do that: deltas must leave while the answer
 * is still arriving, so a marker could in principle straddle an emission
 * boundary and be emitted split. This class closes that gap with a holdback
 * window:</p>
 *
 * <ul>
 *   <li>text is emitted only up to the LAST WHITESPACE of the buffered raw
 *       text — fence markers ({@code <<<UNTRUSTED-XXXXXXXX>>>}) and citation
 *       markers ({@code [12]} / {@code 【12】}) contain no whitespace, so a
 *       whitespace cut can never split one: every marker is judged while
 *       still complete inside the buffered prefix;</li>
 *   <li>a space-free run longer than {@link #MAX_PENDING} (no whitespace to
 *       cut at) forces progress with a marker-opening retreat scan, and an
 *       unreachable-in-practice absolute cap bounds buffering;</li>
 *   <li>at stream end the held tail is flushed through the EXACT
 *       {@code sanitizeAnswer} the blocking path uses.</li>
 * </ul>
 *
 * <p>Parity property: for every answer whose text arrives in any delta
 * split, the concatenation of emitted segments equals
 * {@code sanitizeAnswer(fullText)} — except for text inside a space-free run
 * longer than {@link #ABS_PENDING} chars containing a partial marker at the
 * forced cut (cosmetic residue at worst; unreachable for real answers).</p>
 *
 * <p>NOT thread-safe by design: it is used inside a single-subscription
 * Reactor chain whose onNext signals are serialized (Reactor rule 1.3).</p>
 */
final class StreamSanitizer {

    /** buffered raw text beyond which a forced cut fires even without whitespace */
    static final int MAX_PENDING = 512;
    /** absolute buffering bound — real tutor answers are ≤ ~2000 chars total */
    static final int ABS_PENDING = 8192;
    /** chars always held back on a forced cut (covers any single marker) */
    static final int MIN_TAIL = 8;

    private static final String WHITESPACE = " \t\n\r\u000b\f";

    private final int evidenceCount;
    private final String fenceOpen;
    private final String fenceClose;
    private final StringBuilder pending = new StringBuilder();
    /** T-C40 ③b: every out-of-range citation marker this sanitizer has stripped,
     *  in removal order — the stream-path counterpart of the blocking path's
     *  per-answer stripping log. Read after {@link #flush()} via
     *  {@link #strippedCitations()}. */
    private final List<Integer> strippedCitations = new ArrayList<>();

    StreamSanitizer(int evidenceCount, String fenceOpen, String fenceClose) {
        this.evidenceCount = evidenceCount;
        this.fenceOpen = fenceOpen;
        this.fenceClose = fenceClose;
    }

    /**
     * Absorb one raw delta; return the sanitized text safe to emit now
     * (possibly empty — when everything received could still be the
     * beginning of a marker, nothing is emitted yet).
     */
    String push(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        if (pending.length() > ABS_PENDING) {
            // unreachable for real answers; emit everything but the held tail
            // and accept the documented cosmetic risk rather than buffer forever
            return emitTo(pending.length() - MIN_TAIL);
        }
        int cut = lastWhitespace();
        if (cut >= 0) {
            return emitTo(cut);
        }
        if (pending.length() > MAX_PENDING) {
            // space-free run beyond the window: force progress at a
            // marker-safe point
            return emitTo(retreatFromMarkerOpenings(pending.length() - MIN_TAIL));
        }
        return "";
    }

    /** End of stream: flush the held tail through the exact blocking-path sanitizer. */
    String flush() {
        String rest = GroundedTutorGenerator.sanitizeAnswer(
                pending.toString(), evidenceCount, fenceOpen, fenceClose,
                strippedCitations::add);
        pending.setLength(0);
        return rest == null ? "" : rest;
    }

    /** Every out-of-range citation marker stripped so far, in removal order. */
    List<Integer> strippedCitations() {
        return List.copyOf(strippedCitations);
    }

    private int lastWhitespace() {
        for (int i = pending.length() - 1; i >= 0; i--) {
            if (WHITESPACE.indexOf(pending.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Emit {@code pending[0..cut)} after stripping complete markers from it.
     * The cut must not split a marker: whitespace cuts cannot (no marker
     * contains whitespace); forced cuts are pre-retreated past any potential
     * marker opening. {@code cut <= 0} emits nothing.
     */
    private String emitTo(int cut) {
        if (cut <= 0) {
            return "";
        }
        String prefix = pending.substring(0, cut);
        pending.delete(0, cut);
        return GroundedTutorGenerator.sanitizeAnswer(
                prefix, evidenceCount, fenceOpen, fenceClose, strippedCitations::add);
    }

    /** longest marker we may need to avoid splitting = the fence marker
     *  (25 chars) minus one — any potential opening within this many chars
     *  of the cut could belong to a marker the cut would split */
    private static final int MARKER_LOOKBACK = 24;

    /**
     * Move a forced cut left past every character that could OPEN a marker
     * ({@code [}, {@code 【}, {@code <}) inside the {@link #MARKER_LOOKBACK}
     * window it would otherwise split. The LOWEST opening in the window
     * wins: a "<<<" run is one marker's opening, and stopping at its last
     * '<' would still emit a partial marker. An opening further back than
     * the window cannot span the cut (markers are bounded in length).
     */
    private int retreatFromMarkerOpenings(int cut) {
        int lowest = -1;
        for (int i = cut - 1; i >= 0 && i >= cut - MARKER_LOOKBACK; i--) {
            char c = pending.charAt(i);
            if (c == '[' || c == '【' || c == '<') {
                lowest = i;
            }
        }
        return lowest >= 0 ? lowest : Math.max(0, cut);
    }

    /** Convenience for tests: push every chunk and collect what was emitted. */
    static String streamThrough(StreamSanitizer sanitizer, List<String> chunks) {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append(sanitizer.push(chunk));
        }
        out.append(sanitizer.flush());
        return out.toString();
    }
}
