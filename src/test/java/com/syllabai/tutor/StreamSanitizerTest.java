package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Streamed output hygiene (tutor SSE tranche): the concatenation of emitted
 * segments must equal what the blocking path's {@code sanitizeAnswer} would
 * serve — echoed fence markers and out-of-range citation markers stripped,
 * in-range markers and legitimate text byte-identical — for ANY delta split
 * the provider produces.
 */
class StreamSanitizerTest {

    private static final String FENCE_OPEN = "<<<UNTRUSTED-K7Q2XW9R>>>";
    private static final String FENCE_CLOSE = "<<<END-UNTRUSTED-K7Q2XW9R>>>";
    private static final int EVIDENCE = 3;

    private static StreamSanitizer sanitizer() {
        return new StreamSanitizer(EVIDENCE, FENCE_OPEN, FENCE_CLOSE);
    }

    @Test
    @DisplayName("plain text passes through byte-identical, split at whitespace")
    void plainTextPassThrough() {
        StreamSanitizer s = sanitizer();
        assertThat(s.push("The mole concept")).isEqualTo("The mole");
        assertThat(s.push(" is central")).isEqualTo(" concept is");
        // flush releases the held tail (no trailing whitespace to cut at)
        assertThat(s.flush()).isEqualTo(" central");
    }

    @Test
    @DisplayName("an in-range marker split across deltas survives intact")
    void inRangeMarkerAcrossDeltas() {
        StreamSanitizer s = sanitizer();
        String out = StreamSanitizer.streamThrough(s, List.of(
                "Electrons fill [", "1] the lowest", " energy level first."));
        assertThat(out).isEqualTo("Electrons fill [1] the lowest energy level first.");
    }

    @Test
    @DisplayName("an out-of-range marker split across deltas is stripped")
    void outOfRangeMarkerAcrossDeltas() {
        StreamSanitizer s = sanitizer();
        String out = StreamSanitizer.streamThrough(s, List.of(
                "The claim [", "12] has no evidence."));
        assertThat(out).isEqualTo("The claim  has no evidence.");
    }

    @Test
    @DisplayName("an echoed fence marker split across deltas is stripped (parity with /ask)")
    void fenceEchoAcrossDeltas() {
        StreamSanitizer s = sanitizer();
        String out = StreamSanitizer.streamThrough(s, List.of(
                "Answer start. ", FENCE_OPEN, "injected", FENCE_CLOSE, " answer end."));
        // parity semantics (NOT stricter than /ask): the marker STRINGS are
        // stripped, any echoed content between them remains — exactly what
        // sanitizeAnswer does on the blocking path
        assertThat(out).isEqualTo("Answer start. injected answer end.");
        assertThat(out).doesNotContain(FENCE_OPEN).doesNotContain(FENCE_CLOSE);
    }

    @Test
    @DisplayName("CJK citation markers get the same treatment")
    void cjkMarkers() {
        StreamSanitizer s = sanitizer();
        String out = StreamSanitizer.streamThrough(s, List.of(
                "Data 【", "1】 supported; 【9】 not."));
        assertThat(out).isEqualTo("Data 【1】 supported;  not.");
    }

    @Test
    @DisplayName("parity holds for every split of the same raw text")
    void parityAcrossArbitrarySplits() {
        String fenceOpen = "<<<UNTRUSTED-AB2CD3EF>>>";
        String fenceClose = "<<<END-UNTRUSTED-AB2CD3EF>>>";
        String raw = "Moles are counted " + fenceOpen + " via n=m/MR " + fenceClose
                + " using [1] and [2], but [44] would be fabricated"
                + " per the specification [3].";
        String expected = GroundedTutorGenerator.sanitizeAnswer(raw, EVIDENCE,
                fenceOpen, fenceClose);
        assertThat(expected).doesNotContain("[44]").doesNotContain(fenceOpen);

        // deterministic battery of splits: every 1st, 3rd, 7th, 11th... char
        for (int step : new int[]{1, 3, 7, 11, 23}) {
            StreamSanitizer s = new StreamSanitizer(EVIDENCE, fenceOpen, fenceClose);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < raw.length(); i += step) {
                out.append(s.push(raw.substring(i, Math.min(raw.length(), i + step))));
            }
            out.append(s.flush());
            assertThat(out.toString())
                    .as("split every %d chars", step)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("no whitespace at all: nothing emits before MAX_PENDING, then forced progress")
    void spaceFreeRunForcedProgress() {
        StreamSanitizer s = sanitizer();
        StringBuilder fed = new StringBuilder();
        StringBuilder out = new StringBuilder();
        // 2000 chars with no whitespace and no marker openings: buffering stalls
        // until MAX_PENDING, then the forced cut emits in safe increments
        String run = "x".repeat(2000);
        for (int i = 0; i < run.length(); i += 97) {
            String chunk = run.substring(i, Math.min(run.length(), i + 97));
            fed.append(chunk);
            out.append(s.push(chunk));
        }
        out.append(s.flush());
        assertThat(out.toString()).isEqualTo(run);
    }

    @Test
    @DisplayName("forced progress retreats past a fence the cut would split")
    void forcedCutRetreatsFromMarkerOpening() {
        StreamSanitizer s = sanitizer();
        // long space-free run; the forced cut (length-8) would land INSIDE the
        // echoed fence — the retreat must move the cut before its '<' opening
        String run = "y".repeat(588) + FENCE_OPEN + "junk";
        StringBuilder out = new StringBuilder();
        out.append(s.push(run));
        out.append(s.flush());
        assertThat(out.toString()).isEqualTo("y".repeat(588) + "junk");
    }

    @Test
    @DisplayName("a partial marker fully inside the held tail survives the forced cut")
    void forcedCutRetreatsFromMarkerOpening2() {
        StreamSanitizer s = sanitizer();
        String run = "y".repeat(600) + "[12";
        StringBuilder out = new StringBuilder();
        out.append(s.push(run));
        out.append(s.flush());
        assertThat(out.toString()).isEqualTo("y".repeat(600) + "[12");
    }

    @Test
    @DisplayName("empty pushes are no-ops")
    void emptyPushes() {
        StreamSanitizer s = sanitizer();
        assertThat(s.push("")).isEmpty();
        assertThat(s.push(null)).isEmpty();
        assertThat(s.flush()).isEmpty();
    }
}
