package com.syllabai.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The optional chunk {@code subject_id} projection (the T-C27 trigger-A / at-flip
 * generation's card-axis enabler): a snapshot row may carry subject_id (the
 * post-flip exporter projects it) or not (snap-001..003 do not). The parse must
 * fold missing nodes and JSON nulls to null, keep every other field intact, and
 * leave the manifest count-consistency contract untouched — the recorded r3/r4
 * generations depend on byte-identical behavior for subject-less rows.
 */
class BenchSnapshotSubjectIdTest {

    @TempDir
    Path dir;

    @Test
    void parsesSubjectIdWhenPresentAndFoldsAbsentAndNullToNull() throws Exception {
        writeSnapshot(new String[] {
                "alpha-doc:0", "card content", "SUGGESTED", "EXTERNAL_QUESTIONS", "4CH1/2C",
                "6f7c2f4e59e0c1aa79f8a19fcbd542e2a698a8e2b0df7e2fa4e1b62f5fbd31c0",
                "111e0a25-7a36-4e6f-9c1d-2f5a8b7c6d01"},
                new String[] {
                "beta-doc:0", "paper content", "SUGGESTED", "QUESTION_PAPER", "4CH1/1C",
                "71e0b6c3a2d1f4e8c9b0a7f6e5d4c3b2a190817263544536271809fafbfdce01",
                null});
        BenchSnapshot snapshot = BenchSnapshot.load(dir);

        assertEquals(2, snapshot.chunkCount());
        BenchSnapshot.ChunkRef card = snapshot.chunks().get("alpha-doc:0");
        assertEquals("EXTERNAL_QUESTIONS", card.kind());
        assertEquals("card content", card.content());
        assertEquals("111e0a25-7a36-4e6f-9c1d-2f5a8b7c6d01", card.subjectId());

        BenchSnapshot.ChunkRef paper = snapshot.chunks().get("beta-doc:0");
        assertEquals("QUESTION_PAPER", paper.kind());
        assertNull(paper.subjectId());
    }

    @Test
    void jsonNullSubjectIdFoldsToNullLikeAMissingField() throws Exception {
        // "%%NULL%%" writes an explicit JSON null — the NullNode fold is the
        // Jackson gotcha this test pins (asText(default) must return the default,
        // never the string "null")
        writeSnapshot(new String[] {
                "gamma-doc:0", "content", "SUGGESTED", "MARK_SCHEME", "4CH1/2C",
                "82f1c7d4b3e2a5f9d0c1b8a79685746352414325161728394a5b6c7d8e9f0a1b",
                "%%NULL%%"},
                null);
        BenchSnapshot snapshot = BenchSnapshot.load(dir);
        assertNull(snapshot.chunks().get("gamma-doc:0").subjectId());
        assertEquals("SUGGESTED", snapshot.chunks().get("gamma-doc:0").paperState());
    }

    /**
     * Minimal snap fixture: manifest (files_sha256 + counts) + the gzip chunk
     * array + the four projection files BenchSnapshot always reads. {@code
     * subjectId} null omits the field entirely (snap-001..003 shape); the
     * sentinel "%%NULL%%" writes a JSON null (explicit-null shape).
     */
    private void writeSnapshot(String[] row1, String[] row2) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode chunks = mapper.createArrayNode();
        addChunk(chunks.addObject(), row1, true);
        if (row2 != null) {
            addChunk(chunks.addObject(), row2, true);
        }
        byte[] chunkBytes = mapper.writeValueAsBytes(chunks);

        Map<String, String> sha = new LinkedHashMap<>();
        sha.put("chunks.jsonl.gz", sha256(gzip(chunkBytes)));
        byte[] empty = "[]".getBytes(StandardCharsets.UTF_8);
        sha.put("spec_points.json", sha256(empty));
        sha.put("misconceptions.json", sha256(empty));
        sha.put("graph_edges.json", sha256(empty));
        sha.put("question_anchors.json", sha256(empty));

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("snapshot_version", "snap-subject-test");
        ObjectNode hashes = manifest.putObject("files_sha256");
        sha.forEach(hashes::put);
        manifest.putObject("counts").put("chunks", row2 == null ? 1 : 2);

        Files.write(dir.resolve("chunks.jsonl.gz"), gzip(chunkBytes));
        for (String name : new String[] {"spec_points.json", "misconceptions.json",
                "graph_edges.json", "question_anchors.json"}) {
            Files.write(dir.resolve(name), empty);
        }
        try (OutputStream out = Files.newOutputStream(dir.resolve("manifest.json"))) {
            out.write(mapper.writeValueAsBytes(manifest));
        }
    }

    private static void addChunk(ObjectNode row, String[] f, boolean withSubject) {
        row.put("chunk_ref", f[0]);
        row.put("content", f[1]);
        row.put("content_sha256", f[5]);
        row.put("kind", f[3]);
        row.put("page_start", 1);
        row.put("page_end", 1);
        row.put("paper_code", f[4]);
        row.put("paper_state", f[2]);
        row.putArray("spec_codes");
        row.put("token_estimate", 10);
        if ("%%NULL%%".equals(f[6])) {
            row.putNull("subject_id");
        } else if (withSubject && f[6] != null) {
            row.put("subject_id", f[6]);
        }
    }

    private static byte[] gzip(byte[] data) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
