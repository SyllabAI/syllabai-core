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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional §8(d) chunk→SP HUMAN_VALIDATED projection
 * ({@code chunk_spec_hv.json}, staged for snap-005 by the
 * chunk-sp-substrate-2026-09-27 bridge): absent for snap-001..004 (accessor
 * empty, §8(d) stays NOT SCOREABLE — recorded generations byte-identical);
 * present+manifest-pinned for snap-005+. Fail-closed guards encode the §5
 * counting rule (operator-promoted HUMAN_VALIDATED rows only, never the rule
 * tier) and the bridge contract (MISS rows recorded, never force-matched;
 * unknown chunk_refs abort). Fixture vectors use the REAL committed bridge
 * values (records main 94d0d405c).
 */
class BenchSnapshotChunkSpecHvTest {

    private static final String REF_CLEAN =
            "795770f2c297c26e66b349266212a0b4d0de3d320d8c2e470f8fde15936432ac:1";
    private static final String DOC_CHECKSUM =
            "795770f2c297c26e66b349266212a0b4d0de3d320d8c2e470f8fde15936432ac";

    @TempDir
    Path dir;

    @Test
    void absentProjectionKeepsRecordedBehavior() throws Exception {
        writeSnapshot(false, null);
        BenchSnapshot snapshot = BenchSnapshot.load(dir);
        assertFalse(snapshot.chunkSpecHvPresent());
        assertTrue(snapshot.hvSpecCodesByChunkRef().isEmpty());
        assertEquals(0, snapshot.chunkSpecHvCensus().rows());
    }

    @Test
    void presentButUnpinnedFailsClosed() throws Exception {
        writeSnapshot(false, realProjection());
        // file exists, manifest does not pin it -> load must refuse
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    @Test
    void parsesRealBridgeVectorsAndCensus() throws Exception {
        writeSnapshot(true, realProjection());
        BenchSnapshot snapshot = BenchSnapshot.load(dir);

        assertTrue(snapshot.chunkSpecHvPresent());
        // CLEAN vector: the real first bridge row (mapping efc19a2773331632, 4CH1-1.1)
        // anchors the shared ref — many-to-many: other rows' codes ride the same ref
        var codesOnSharedRef = snapshot.hvSpecCodesByChunkRef().get(REF_CLEAN);
        assertTrue(codesOnSharedRef.contains("4CH1-1.1"));
        assertTrue(codesOnSharedRef.contains("4CH1-1.2"));
        assertTrue(codesOnSharedRef.contains("4CH1-2.4"));

        // many-to-many: the MULTI row's three refs all carry its code
        BenchSnapshot.ChunkSpecHvCensus census = snapshot.chunkSpecHvCensus();
        // fixture: 5 rows (3 CLEAN + 1 MULTI + 1 MISS), 4 with refs, 5 distinct codes
        assertEquals(5, census.rows());
        assertEquals(4, census.rowsWithRefs());
        assertEquals(5, census.distinctCodes());
        assertEquals(1, census.missCodes().size());
        assertEquals("4CH1-1.52C", census.missCodes().get(0));
        // the MISS row contributes no refs: its code is covered by NO chunk_ref
        // (recorded as a miss, never force-matched — bridge contract)
        snapshot.hvSpecCodesByChunkRef().values()
                .forEach(set -> assertFalse(set.contains("4CH1-1.52C")));
    }

    @Test
    void nonHumanValidatedRowFailsClosed() throws Exception {
        String projection = realProjection().replace(
                "\"validation_status\": \"HUMAN_VALIDATED\"",
                "\"validation_status\": \"SUGGESTED\"");
        writeSnapshot(true, projection);
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    @Test
    void unknownChunkRefFailsClosed() throws Exception {
        String projection = realProjection().replace(DOC_CHECKSUM + ":4",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff:4");
        writeSnapshot(true, projection);
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    @Test
    void duplicateMappingIdFailsClosed() throws Exception {
        // duplicate the CLEAN row's mapping id onto the MISS row
        String projection = realProjection().replace("\"1d97fd710f098a74\"",
                "\"efc19a2773331632\"");
        writeSnapshot(true, projection);
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    @Test
    void malformedSpecCodeFailsClosed() throws Exception {
        String projection = realProjection().replace("4CH1-1.52C", "1.52C");
        writeSnapshot(true, projection);
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    // ── handoff §4 census gate (fail-closed against the manifest record) ───

    @Test
    void censusDriftAgainstManifestFailsClosed() throws Exception {
        // the manifest declares snap-005's real census (210/209/164/181) but
        // the pinned artifact parses the 5-row fixture census -> drift aborts
        writeSnapshot(true, realProjection(), """
                {"rows": 210, "rows_with_refs": 209, "distinct_chunk_refs": 164,
                 "distinct_spec_codes": 181,
                 "anchor_kinds": {"CLEAN": 205, "MULTI": 4, "MISS": 1}}""");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BenchSnapshot.load(dir));
        assertTrue(ex.getMessage().contains("census drift"), ex.getMessage());
    }

    @Test
    void missingManifestCensusBlockFailsClosed() throws Exception {
        // the file is pinned but counts.hv_projection is absent -> the export
        // contract records the census; a manifest without it never scores
        writeSnapshot(true, realProjection(), null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BenchSnapshot.load(dir));
        assertTrue(ex.getMessage().contains("counts.hv_projection"), ex.getMessage());
    }

    @Test
    void censusGateAcceptsTheMatchingManifestRecord() throws Exception {
        // the declared census equals the parsed census -> loads clean
        // (fixture: 5 rows / 4 with refs / 3 distinct refs / 5 codes / 1 miss)
        writeSnapshot(true, realProjection(), """
                {"rows": 5, "rows_with_refs": 4, "distinct_chunk_refs": 3,
                 "distinct_spec_codes": 5,
                 "anchor_kinds": {"CLEAN": 3, "MULTI": 1, "MISS": 1}}""");
        BenchSnapshot snapshot = BenchSnapshot.load(dir);
        assertTrue(snapshot.chunkSpecHvPresent());
        assertEquals(5, snapshot.chunkSpecHvCensus().rows());
    }

    // ── fixture ───────────────────────────────────────────────────────────

    /**
     * The real bridge values (subset): 1 CLEAN row (4CH1-1.1), 2 CLEAN rows
     * sharing a second ref (many-to-many), 1 MULTI row over 3 refs
     * (c19e1b3cca1d9eee, indexes 1/2/4), 1 MISS row (1d97fd710f098a74,
     * 4CH1-1.52C, no refs). 5 rows / 4 with refs / 5 distinct codes
     * / 1 miss on the fixture.
     */
    private static String realProjection() {
        return """
                {"meta": {"bridge": "chunk-sp-substrate-2026-09-27 fixture"},
                 "rows": [
                  {"mapping_id": "efc19a2773331632", "spec_code": "4CH1-1.1",
                   "chunk_refs": ["%s:1"],
                   "anchor_kind": "CLEAN",
                   "provenance": {"tier": "RULE_DERIVED", "validation_status": "HUMAN_VALIDATED",
                                  "promoted_by": "operator-directive-session-102"}},
                  {"mapping_id": "aaa19a2773331632", "spec_code": "4CH1-1.2",
                   "chunk_refs": ["%s:1", "%s:2"],
                   "anchor_kind": "CLEAN",
                   "provenance": {"tier": "RULE_DERIVED", "validation_status": "HUMAN_VALIDATED"}},
                  {"mapping_id": "bbb19a2773331632", "spec_code": "4CH1-1.3",
                   "chunk_refs": ["%s:2"],
                   "anchor_kind": "CLEAN",
                   "provenance": {"tier": "RULE_DERIVED", "validation_status": "HUMAN_VALIDATED"}},
                  {"mapping_id": "c19e1b3cca1d9eee", "spec_code": "4CH1-2.4",
                   "chunk_refs": ["%s:1", "%s:2", "%s:4"],
                   "anchor_kind": "MULTI",
                   "provenance": {"tier": "RULE_DERIVED", "validation_status": "HUMAN_VALIDATED"}},
                  {"mapping_id": "1d97fd710f098a74", "spec_code": "4CH1-1.52C",
                   "chunk_refs": [],
                   "anchor_kind": "MISS",
                   "provenance": {"tier": "RULE_DERIVED", "validation_status": "HUMAN_VALIDATED"}}
                 ]}
                """.formatted(DOC_CHECKSUM, DOC_CHECKSUM, DOC_CHECKSUM,
                DOC_CHECKSUM, DOC_CHECKSUM, DOC_CHECKSUM, DOC_CHECKSUM);
    }

    private void writeSnapshot(boolean pinHv, String hvJson) throws Exception {
        // fixture census derived from realProjection(): 5 rows / 4 with refs /
        // 3 distinct refs (:1 :2 :4) / 5 distinct codes / CLEAN 3 + MULTI 1 + MISS 1
        writeSnapshot(pinHv, hvJson, """
                {"rows": 5, "rows_with_refs": 4, "distinct_chunk_refs": 3,
                 "distinct_spec_codes": 5,
                 "anchor_kinds": {"CLEAN": 3, "MULTI": 1, "MISS": 1}}""");
    }

    /**
     * Full fixture: {@code withCensus == null} omits counts.hv_projection
     * (the missing-block guard); otherwise the manifest declares the given
     * census block alongside the pin.
     */
    private void writeSnapshot(boolean pinHv, String hvJson, String withCensus)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode chunks = mapper.createArrayNode();
        // the five note-chunk refs the fixture projection points at
        for (int i = 0; i <= 4; i++) {
            ObjectNode row = chunks.addObject();
            row.put("chunk_ref", DOC_CHECKSUM + ":" + i);
            row.put("content", "note chunk " + i);
            row.put("content_sha256", DOC_CHECKSUM);
            row.put("kind", "EXTERNAL_NOTES");
            row.put("page_start", 1);
            row.put("page_end", 1);
            row.putNull("paper_code");
            row.put("paper_state", "SUGGESTED");
            row.putArray("spec_codes");
            row.put("token_estimate", 10);
        }
        byte[] chunkBytes = mapper.writeValueAsBytes(chunks);

        Map<String, String> sha = new LinkedHashMap<>();
        sha.put("chunks.jsonl.gz", sha256(gzip(chunkBytes)));
        byte[] empty = "[]".getBytes(StandardCharsets.UTF_8);
        sha.put("spec_points.json", sha256(empty));
        sha.put("misconceptions.json", sha256(empty));
        sha.put("graph_edges.json", sha256(empty));
        sha.put("question_anchors.json", sha256(empty));
        byte[] hvBytes = hvJson == null ? new byte[0] : hvJson.getBytes(StandardCharsets.UTF_8);
        if (pinHv) {
            sha.put("chunk_spec_hv.json", sha256(hvBytes));
        }

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("snapshot_version", "snap-s8d-test");
        ObjectNode hashes = manifest.putObject("files_sha256");
        sha.forEach(hashes::put);
        ObjectNode counts = manifest.putObject("counts");
        counts.put("chunks", 5);
        if (pinHv && hvJson != null && withCensus != null) {
            counts.set("hv_projection", mapper.readTree(withCensus));
        }

        Files.write(dir.resolve("chunks.jsonl.gz"), gzip(chunkBytes));
        for (String name : new String[] {"spec_points.json", "misconceptions.json",
                "graph_edges.json", "question_anchors.json"}) {
            Files.write(dir.resolve(name), empty);
        }
        if (hvJson != null) {
            Files.write(dir.resolve("chunk_spec_hv.json"), hvBytes);
        }
        try (OutputStream out = Files.newOutputStream(dir.resolve("manifest.json"))) {
            out.write(mapper.writeValueAsBytes(manifest));
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
