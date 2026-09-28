package com.syllabai.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deep-audit 09-28 M3: ZIP extraction is bounded BEFORE allocation — a tiny
 * archive must never be able to decompress into unbounded memory (the classic
 * 42 KB ZIP bomb OOMed the service through {@code readAllBytes()}). The walker
 * enforces per-entry, total and entry-count budgets mid-stream, plus a
 * structural traversal guard on entry names.
 */
class ZipSafetyTest {

    private final List<String> names = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private final ZipSafety.EntryConsumer collect = (name, data) -> {
        names.add(name);
        payloads.add(data);
    };

    // ── fixtures ──────────────────────────────────────────────────────────

    private static byte[] zipOf(List<String[]> pairs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String[] pair : pairs) {
                zos.putNextEntry(new ZipEntry(pair[0]));
                zos.write(pair[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] compressibleZip(int uncompressedBytes) throws IOException {
        // a real "bomb shape": ~1000:1 ratio (zeros deflate to nearly nothing)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("package.json"));
            zos.write(new byte[uncompressedBytes]);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static ZipSafety.Limits limits(long entry, long total, int count) {
        return new ZipSafety.Limits(entry, total, count);
    }

    // ── the walker's contract ─────────────────────────────────────────────

    @Test
    @DisplayName("entries stream through in order; directories are skipped")
    void happyPath() throws IOException {
        byte[] archive = zipOf(List.of(
                new String[] {"package.json", "{}"},
                new String[] {"assets/one.png", "binary-bytes"},
                new String[] {"notes/a.md", "hello"}));
        ZipSafety.readEach(archive, ZipSafety.Limits.defaults(), collect);
        assertThat(names).containsExactly("package.json", "assets/one.png", "notes/a.md");
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(new String(payloads.get(2), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    @DisplayName("M3: a highly compressible entry that decompresses past the "
            + "per-entry budget is rejected mid-stream (the bomb defense)")
    void perEntryCapStopsTheBomb() throws IOException {
        // ~100 KB of zeros compresses to ~100 bytes — 1000:1 ratio
        byte[] bomb = compressibleZip(100_000);
        assertThatThrownBy(() -> ZipSafety.readEach(bomb,
                limits(1_000, 1_000_000, 100), collect))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("per-entry budget");
        assertThat(names).isEmpty();   // rejected BEFORE any consumer saw data
    }

    @Test
    @DisplayName("M3: the total-uncompressed budget caps a many-entry archive")
    void totalCapStopsManyEntries() throws IOException {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pairs.add(new String[] {"f" + i + ".txt", "x".repeat(400)});
        }
        byte[] archive = zipOf(pairs);
        assertThatThrownBy(() -> ZipSafety.readEach(archive,
                limits(1_000, 1_000, 100), collect))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("total budget");
    }

    @Test
    @DisplayName("M3: the entry-count budget caps archive breadth")
    void entryCountCap() throws IOException {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pairs.add(new String[] {"f" + i + ".txt", "tiny"});
        }
        byte[] archive = zipOf(pairs);
        assertThatThrownBy(() -> ZipSafety.readEach(archive,
                limits(1_000, 1_000_000, 5), collect))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("more than 5 entries");
    }

    // ── traversal guard ───────────────────────────────────────────────────

    @Test
    @DisplayName("M3: upward-resolving, absolute, backslash and drive-letter "
            + "names are structurally rejected")
    void traversalGuard() throws IOException {
        for (String bad : new String[] {"../evil.txt", "a/../../evil.txt",
                "/abs.txt", "a\\b.txt", "C:evil.txt"}) {
            byte[] archive = zipOf(java.util.Collections.singletonList(new String[] {bad, "x"}));
            assertThatThrownBy(() -> ZipSafety.readEach(archive,
                    ZipSafety.Limits.defaults(), collect))
                    .as("entry name '%s' must be rejected", bad)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("unsafe entry path");
        }
    }

    @Test
    @DisplayName("M3: plain dotted names stay legal (no false positives)")
    void dottedNamesStayLegal() throws IOException {
        byte[] archive = zipOf(List.of(new String[] {"a..b.png", "x"},
                new String[] {"v1.2/notes.md", "y"}));
        ZipSafety.readEach(archive, ZipSafety.Limits.defaults(), collect);
        assertThat(names).containsExactly("a..b.png", "v1.2/notes.md");
    }

    @Test
    @DisplayName("M3: a structurally broken archive surfaces IOException (callers "
            + "keep their 'not a ZIP' translation); prose garbage simply looks "
            + "like an empty archive (ZipInputStream's signature leniency)")
    void malformedArchivePropagatesIo() {
        // ZipInputStream treats a LOC-signature mismatch as END of archive —
        // plain prose yields zero entries (each service then rejects with its
        // "package.json missing" 400). A VALID signature followed by garbage
        // that cannot parse is the honest "broken archive" shape: EOFException.
        byte[] broken = new byte[64];
        broken[0] = 'P';
        broken[1] = 'K';
        broken[2] = 3;
        broken[3] = 4;   // LOCSIG — promises a local file header
        for (int i = 4; i < broken.length; i++) {
            broken[i] = (byte) 0xAB;
        }
        assertThatThrownBy(() -> ZipSafety.readEach(broken,
                ZipSafety.Limits.defaults(), collect))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("M3: prose garbage reads as an empty archive — the caller's "
            + "package.json-missing rejection covers it")
    void proseGarbageYieldsNoEntries() throws IOException {
        byte[] garbage = ("this stream is definitely not a zip archive at all, "
                + "it is just prose padded past the local-file-header length")
                .getBytes(StandardCharsets.UTF_8);
        ZipSafety.readEach(garbage, ZipSafety.Limits.defaults(), collect);
        assertThat(names).isEmpty();
    }
}
