package com.syllabai.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring test: the REAL production {@code GraphKnowledgeRetriever} +
 * {@code ReciprocalRankFusion} run over a synthetic frozen snapshot through the
 * bench stubs. Cases mirror the production calibration examples documented on
 * the retriever ("moles" onto "Mole calculations"; a single generic token onto
 * a long spec title) plus the fail-closed snapshot verification.
 */
class ArmA0WiringTest {

    private static final String SPEC_11 = "4CH1-1.1";
    private static final String SPEC_12 = "4CH1-1.2";
    private static final String SPEC_21 = "4CH1-2.1";

    @TempDir
    Path dir;

    @Test
    void multiTokenMatchRanksTheNamedTopic() throws Exception {
        ArmA0.A0Result result = arm().run("mole calculations");
        assertEquals(1, result.rankedTopics().size());
        assertEquals(SPEC_11, result.rankedTopics().get(0).code());
        assertEquals(List.of(SPEC_11), result.fusedOrder());   // single-list RRF = identity
    }

    @Test
    void singleTokenAcceptedOnlyAboveSpecificityFloor() throws Exception {
        // "halogens" -> token "halogen" = 1/2 of "recall the halogens" (0.50 floor holds)
        assertEquals(SPEC_21, arm().run("halogens").fusedOrder().get(0));
        // "understand" = 1/4 and 1/9 - both below the 0.50 floor -> nothing matches
        assertTrue(arm().run("understand").fusedOrder().isEmpty());
    }

    @Test
    void prerequisiteClosureIsDeepestFirst() throws Exception {
        // 2.1 requires 1.1 requires 1.2 — closure of the matched 2.1 walks both
        ArmA0.A0Result result = arm().run("halogens");
        assertEquals(List.of(SPEC_21), result.fusedOrder());
        assertEquals(List.of(SPEC_12, SPEC_11), result.prerequisiteCodes());
    }

    @Test
    void misconceptionSignalIsRecorded() throws Exception {
        ArmA0.A0Result result = arm().run("mole calculations");
        assertEquals(List.of(SPEC_11), result.fusedOrder());
        assertEquals(List.of("4CH1-MIS-X"), result.misconceptionCodes());
    }

    @Test
    void runIsDeterministic() throws Exception {
        ArmA0 arm = arm();
        ArmA0.A0Result first = arm.run("mole calculations");
        ArmA0.A0Result second = arm.run("mole calculations");
        assertEquals(first.fusedOrder(), second.fusedOrder());
        assertEquals(first.rankedTopics(), second.rankedTopics());
        assertEquals(first.prerequisiteCodes(), second.prerequisiteCodes());
    }

    @Test
    void corruptedSnapshotFailsClosed() throws Exception {
        writeSnapshot(false);
        assertThrows(IllegalStateException.class, () -> BenchSnapshot.load(dir));
    }

    private ArmA0 arm() throws Exception {
        writeSnapshot(true);
        return new ArmA0(BenchSnapshot.load(dir));
    }

    private void writeSnapshot(boolean honest) throws Exception {
        String specPoints = "["
                + "{\"code\":\"" + SPEC_11 + "\",\"node_type\":\"SUBTOPIC\","
                + "\"title\":\"understand mole calculations in chemistry\",\"validation_status\":\"VALIDATED\"},"
                + "{\"code\":\"" + SPEC_12 + "\",\"node_type\":\"SUBTOPIC\","
                + "\"title\":\"understand how to plot and interpret solubility curves\","
                + "\"validation_status\":\"VALIDATED\"},"
                + "{\"code\":\"" + SPEC_21 + "\",\"node_type\":\"SUBTOPIC\","
                + "\"title\":\"recall the halogens\",\"validation_status\":\"VALIDATED\"}]";
        String edges = "["
                + "{\"relation\":\"REQUIRES_PREREQUISITE\",\"source\":\"" + SPEC_21 + "\",\"target\":\"" + SPEC_11 + "\"},"
                + "{\"relation\":\"REQUIRES_PREREQUISITE\",\"source\":\"" + SPEC_11 + "\",\"target\":\"" + SPEC_12 + "\"},"
                + "{\"relation\":\"MISCONCEPTION_OF\",\"source\":\"4CH1-MIS-X\",\"target\":\"" + SPEC_11 + "\"}]";
        String misconceptions = "[{\"code\":\"4CH1-MIS-X\",\"title\":\"counting bonds wrongly\"}]";
        String anchors = "[]";
        String chunks = "[{\"chunk_ref\":\"aaa:0\",\"content\":\"mole calculations practice\","
                + "\"paper_state\":\"VALIDATED\",\"document_kind\":\"QUESTION_PAPER\"},"
                + "{\"chunk_ref\":\"bbb:0\",\"content\":\"halogens trends\",\"paper_state\":\"SUGGESTED\","
                + "\"document_kind\":\"MARK_SCHEME\"}]";

        Files.writeString(dir.resolve("spec_points.json"), specPoints, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("graph_edges.json"), edges, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("misconceptions.json"), misconceptions, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("question_anchors.json"), anchors, StandardCharsets.UTF_8);
        Path chunksGz = dir.resolve("chunks.jsonl.gz");
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(chunksGz))) {
            gz.write(chunks.getBytes(StandardCharsets.UTF_8));
        }

        String manifest = "{\"snapshot_version\":\"snap-test\",\"files_sha256\":{"
                + "\"spec_points.json\":\"" + sha(dir.resolve("spec_points.json")) + "\","
                + "\"graph_edges.json\":\"" + (honest ? sha(dir.resolve("graph_edges.json"))
                        : "0".repeat(64)) + "\","
                + "\"misconceptions.json\":\"" + sha(dir.resolve("misconceptions.json")) + "\","
                + "\"question_anchors.json\":\"" + sha(dir.resolve("question_anchors.json")) + "\","
                + "\"chunks.jsonl.gz\":\"" + sha(chunksGz) + "\"},"
                + "\"counts\":{\"chunks\":2,\"edges\":3,\"misconceptions\":1,"
                + "\"question_anchors\":0,\"spec_points\":3,\"concept_attachments\":0}}";
        Files.writeString(dir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
