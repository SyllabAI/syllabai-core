package com.syllabai.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bounded ZIP entry walker shared by every ZIP-consuming ingestion surface
 * (deep-audit 09-28 M3: both unzippers read entries with
 * {@code ZipInputStream.readAllBytes()}, which allocates the FULL uncompressed
 * entry before any check can run — a small "ZIP bomb" archive (42 KB is the
 * classic demo) would decompress into gigabytes and OOM the single-instance
 * service. The multipart layer caps the COMPRESSED upload at 64 MB, which
 * bounds nothing here: compression ratio is attacker-chosen.)
 *
 * <p>Three absolute budgets, enforced DURING streaming (never after
 * allocation): a per-entry uncompressed cap, a total-uncompressed cap, and an
 * entry-count cap. All three fail closed with a {@link BadRequestException}
 * naming the budget. A structural traversal guard rejects entry names that
 * could escape a target directory (absolute paths, {@code ../} segments,
 * drive letters, backslash separators) — the current callers never write to
 * the filesystem, but the guard makes that assumption explicit and cheap.</p>
 *
 * <p>The caps are deliberately absolute rather than ratio-based: a ratio check
 * needs the compressed size of each entry (only the first entry carries a
 * reliable size header in a streamed ZIP) and still permits a slowly-fed
 * unbounded total. Absolute caps bound memory at exactly the configured size,
 * which is the property the single-pod deployment actually needs.</p>
 */
public final class ZipSafety {

    /** default per-entry uncompressed ceiling (matches the multipart cap) */
    static final long DEFAULT_MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    /** default total uncompressed ceiling across all entries in one archive */
    static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    /** default entry-count ceiling */
    static final int DEFAULT_MAX_ENTRIES = 10_000;
    /** streaming read chunk (small by design — the walker must never rely on
     *  entry size headers, which are attacker-controlled) */
    private static final int CHUNK = 8 * 1024;

    /**
     * @param maxEntryBytes uncompressed ceiling per entry
     * @param maxTotalBytes uncompressed ceiling for the whole archive
     * @param maxEntries    maximum number of file entries visited
     */
    public record Limits(long maxEntryBytes, long maxTotalBytes, int maxEntries) {
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES,
                    DEFAULT_MAX_ENTRIES);
        }
    }

    /** consumer for each non-directory entry (name = raw entry name) */
    @FunctionalInterface
    public interface EntryConsumer {
        void accept(String name, byte[] data) throws IOException;
    }

    private ZipSafety() {
    }

    /**
     * Streams every non-directory entry of the archive through {@code consumer}
     * under the given budgets. Throws {@link BadRequestException} when a budget
     * is exceeded or an entry name is structurally unsafe; {@link IOException}
     * propagates for genuinely malformed archives (callers keep their existing
     * "not a readable ZIP" translation).
     */
    public static void readEach(byte[] zipBytes, Limits limits, EntryConsumer consumer)
            throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            int entries = 0;
            long total = 0;
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > limits.maxEntries()) {
                    throw new BadRequestException(
                            "archive contains more than " + limits.maxEntries() + " entries");
                }
                requireSafeName(entry.getName());
                byte[] data = readBounded(zin, entry.getName(), limits, total);
                total += data.length;
                if (total > limits.maxTotalBytes()) {
                    throw new BadRequestException("archive decompresses beyond the "
                            + limits.maxTotalBytes() + " byte total budget");
                }
                consumer.accept(entry.getName(), data);
            }
        }
    }

    /** reads at most {@code maxEntryBytes} bytes of the current entry, aborting
     *  mid-stream the moment the budget is crossed (never allocates past it) */
    private static byte[] readBounded(ZipInputStream zin, String name, Limits limits,
                                      long totalBefore) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(64 * 1024,
                (int) Math.min(limits.maxEntryBytes(), Integer.MAX_VALUE - 1)));
        byte[] buffer = new byte[CHUNK];
        long entryTotal = 0;
        int read;
        while ((read = zin.read(buffer)) != -1) {
            entryTotal += read;
            if (entryTotal > limits.maxEntryBytes()) {
                throw new BadRequestException("archive entry '" + name
                        + "' decompresses beyond the " + limits.maxEntryBytes()
                        + " byte per-entry budget");
            }
            if (totalBefore + entryTotal > limits.maxTotalBytes()) {
                throw new BadRequestException("archive decompresses beyond the "
                        + limits.maxTotalBytes() + " byte total budget");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Structural traversal guard: rejects absolute paths, any {@code ..} path
     * segment (on either separator), backslash separators, and drive-letter
     * prefixes. Plain names containing dots (e.g. {@code a..b.png}) stay legal —
     * only segments that RESOLVE upward are refused.
     */
    private static void requireSafeName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("archive contains an entry with an empty name");
        }
        if (name.contains("\\") || name.startsWith("/")
                || name.length() >= 2 && Character.isLetter(name.charAt(0))
                        && name.charAt(1) == ':') {
            throw new BadRequestException(
                    "archive contains an unsafe entry path: " + safeNameForLog(name));
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new BadRequestException(
                        "archive contains an unsafe entry path: " + safeNameForLog(name));
            }
        }
    }

    /** log-safe name rendering: bounded length, decoded as UTF-8 for the message */
    private static String safeNameForLog(String name) {
        String rendered = new String(name.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        return rendered.length() > 100 ? rendered.substring(0, 100) + "…" : rendered;
    }
}
