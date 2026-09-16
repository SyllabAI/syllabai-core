package com.syllabai.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * T-C13 harness (spec §3.4, §6): loads the frozen gold set v1 and verifies
 * every class file against the manifest SHA-256 BEFORE anything runs —
 * fail-closed, mirroring {@code bench/gold_check.py}. Also enforces the
 * cross-file uniqueness of query ids and the quota-table totals recorded in
 * the manifest (a set that fails validation can never reach the scorer).
 */
public final class BenchGold {

    public record GoldEvidence(String chunkRef, int tier, String rule) {
    }

    public record GoldRecord(String id, String className, String query,
                             List<String> goldSpecPoints, List<GoldEvidence> goldEvidence,
                             String substrate, String labelRule, String labelSource) {
    }

    private final List<GoldRecord> records = new ArrayList<>();
    private final JsonNode manifest;

    private BenchGold(Path dir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        this.manifest = mapper.readTree(Files.readAllBytes(dir.resolve("manifest.json")));
        JsonNode hashes = manifest.path("files_sha256");
        List<String> names = new ArrayList<>();
        hashes.fieldNames().forEachRemaining(names::add);
        List<String> classFiles = names.stream().filter(n -> n.startsWith("class_")).sorted().toList();

        int total = 0;
        List<String> seenIds = new ArrayList<>();
        for (String name : classFiles) {
            BenchSnapshotHash.verify(dir.resolve(name), hashes.path(name).asText(), name);
            for (JsonNode r : mapper.readTree(Files.readAllBytes(dir.resolve(name)))) {
                List<GoldEvidence> evidence = new ArrayList<>();
                for (JsonNode e : r.path("gold_evidence")) {
                    evidence.add(new GoldEvidence(e.path("chunk_ref").asText(),
                            e.path("tier").asInt(), e.path("rule").asText(null)));
                }
                List<String> points = new ArrayList<>();
                r.path("gold_spec_points").forEach(p -> points.add(p.asText()));
                JsonNode provenance = r.path("provenance");
                GoldRecord rec = new GoldRecord(r.path("id").asText(),
                        r.path("class").asText(), r.path("query").asText(),
                        List.copyOf(points), List.copyOf(evidence),
                        r.path("substrate").asText(null),
                        provenance.path("rule").asText(null),
                        provenance.path("source").asText(null));
                if (seenIds.contains(rec.id())) {
                    throw new IllegalStateException("duplicate gold id " + rec.id() + " (fail-closed)");
                }
                seenIds.add(rec.id());
                records.add(rec);
                total++;
            }
        }
        int declaredTotal = manifest.path("counts").path("total").asInt(-1);
        if (declaredTotal >= 0 && total != declaredTotal) {
            throw new IllegalStateException("gold manifest declares " + declaredTotal
                    + " records but files hold " + total + " (fail-closed)");
        }
    }

    public static BenchGold load(Path dir) throws IOException {
        return new BenchGold(dir);
    }

    public List<GoldRecord> records() {
        return List.copyOf(records);
    }

    public JsonNode manifest() {
        return manifest;
    }

    /** SHA-256 helper shared with {@link BenchSnapshot}-style verification. */
    static final class BenchSnapshotHash {
        static void verify(Path file, String expectedSha, String name) throws IOException {
            if (!Files.exists(file)) {
                throw new IllegalStateException("gold file missing: " + name + " (fail-closed)");
            }
            String actual = Sha256.of(file);
            if (!actual.equalsIgnoreCase(expectedSha)) {
                throw new IllegalStateException("gold file " + name + " SHA-256 mismatch (fail-closed): "
                        + actual + " != " + expectedSha);
            }
        }
    }

    static final class Sha256 {
        static String of(Path file) throws IOException {
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
