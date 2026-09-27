package com.syllabai.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §8(d) scorer semantics (pure, deterministic): covered set = union of HV
 * codes over the served ranked refs; BOTH granularity readings reported
 * (full-coverage per query + per-point micro-average) because no §10 ruling
 * pins the choice and the recorded generations scored NOT SCOREABLE (no
 * precedent). Honest-zero rule: empty served lists score as zeros, never
 * excluded. Unbridged gold codes surface as explicit misses.
 */
class ChunkSpecHvResolutionTest {

    private static final Map<String, Set<String>> HV = Map.of(
            "ref-a:1", Set.of("4CH1-1.1", "4CH1-1.2"),
            "ref-a:2", Set.of("4CH1-1.3"),
            "ref-b:0", Set.of("4CH1-2.1"));

    @Test
    void singleSpecPointQueryScoresBothReadingsEqual() {
        var r = ChunkSpecHvResolution.scoreQuery("q1", List.of("ref-a:1", "ref-b:0"),
                List.of("4CH1-1.1"), HV);
        assertEquals(1, r.goldN());
        assertEquals(1, r.coveredN());
        assertTrue(r.fullCoverage());
        assertEquals(1.0, r.perPointCoverage());
    }

    @Test
    void multiSpecPointQuerySeparatesTheTwoGranularities() {
        // gold {1.1, 1.3, 2.5}: covered = {1.1, 1.3, 2.1} -> 2 of 3, not full
        var r = ChunkSpecHvResolution.scoreQuery("q2", List.of("ref-a:1", "ref-a:2", "ref-b:0"),
                List.of("4CH1-1.1", "4CH1-1.3", "4CH1-2.5"), HV);
        assertFalse(r.fullCoverage());
        assertEquals(2, r.coveredN());
        assertEquals(3, r.goldN());
        assertEquals(2.0 / 3.0, r.perPointCoverage(), 1e-12);
    }

    @Test
    void emptyServedListIsAnHonestZeroNotAnExclusion() {
        var r = ChunkSpecHvResolution.scoreQuery("q3", List.of(),
                List.of("4CH1-1.1"), HV);
        assertFalse(r.fullCoverage());
        assertEquals(0.0, r.perPointCoverage());
        var agg = ChunkSpecHvResolution.aggregate(List.of(r));
        assertEquals(0.0, agg.get("spec_points_full_coverage_rate"));
        assertEquals(0.0, agg.get("spec_points_micro_average"));
        assertEquals(1, ((Number) agg.get("queries_scored")).intValue());
        assertEquals(1L, agg.get("gold_points_total"));
        assertEquals(0L, agg.get("gold_points_covered"));
    }

    @Test
    void aggregateComputesBothGranularitiesAndRoundsTo4dp() {
        var rows = List.of(
                ChunkSpecHvResolution.scoreQuery("q1", List.of("ref-a:1"),
                        List.of("4CH1-1.1", "4CH1-1.2"), HV),            // full, 2/2
                ChunkSpecHvResolution.scoreQuery("q2", List.of("ref-a:2"),
                        List.of("4CH1-1.1", "4CH1-1.3"), HV),            // 1.3 covered, 1/2
                ChunkSpecHvResolution.scoreQuery("q3", List.of("ref-b:0"),
                        List.of("4CH1-2.1"), HV));                        // full, 1/1
        Map<String, Object> agg = ChunkSpecHvResolution.aggregate(rows);
        // full coverage: q1 + q3 -> 2/3 = 0.6667
        assertEquals(0.6667, agg.get("spec_points_full_coverage_rate"));
        // micro-average: covered 4 / gold 5 = 0.8
        assertEquals(0.8, agg.get("spec_points_micro_average"));
        assertEquals(3, agg.get("queries_scored"));
        assertEquals(5L, agg.get("gold_points_total"));
        assertEquals(4L, agg.get("gold_points_covered"));
    }

    @Test
    void aggregateIsEmptyMapForNoRows() {
        assertEquals(Map.of(), ChunkSpecHvResolution.aggregate(List.of()));
    }

    @Test
    void unbridgedGoldPointsSurfaceAsExplicitMisses() {
        List<String> misses = ChunkSpecHvResolution.unbridgedGoldPoints(
                List.of("4CH1-1.1", "4CH1-1.52C", "4CH1-4.15"), HV);
        // 1.52C (the bridge's MISS code) and 4.15 (the store's worklist gap)
        // are structurally unbridged; 1.1 is bridged and must NOT be listed
        assertEquals(List.of("4CH1-1.52C", "4CH1-4.15"), misses);
    }

    @Test
    void aggregateSerializationIsByteStableAcrossRecomputation() {
        List<ChunkSpecHvResolution.QueryResolution> rows = List.of(
                ChunkSpecHvResolution.scoreQuery("q1", List.of("ref-a:1"),
                        List.of("4CH1-1.1"), HV),
                ChunkSpecHvResolution.scoreQuery("q1", List.of("ref-a:1"),
                        List.of("4CH1-1.1"), HV));
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        try {
            String one = mapper.writeValueAsString(ChunkSpecHvResolution.aggregate(rows));
            String two = mapper.writeValueAsString(ChunkSpecHvResolution.aggregate(rows));
            assertEquals(one, two);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(((Number) ChunkSpecHvResolution.aggregate(rows)
                .get("spec_points_full_coverage_rate")).doubleValue() == 1.0);
    }

    @Test
    void reportLineReturnsAbsentTextVerbatimWhenSectionMissing() {
        String absent = "- SpecificationPoint resolution: NOT SCOREABLE (original arm text).\n";
        assertEquals(absent, ChunkSpecHvResolution.reportLine(Map.of(), absent));
    }

    @Test
    void sectionCarriesCensusCaveatAndReportLineReadsTheAllView() {
        // minimal snapshot stub is impossible (final class) — exercise section via
        // the real loader fixture path instead: reuse BenchSnapshotChunkSpecHvTest
        // for census; here verify reportLine reads a section-shaped map directly.
        Map<String, Object> agg = ChunkSpecHvResolution.aggregate(List.of(
                ChunkSpecHvResolution.scoreQuery("q1", List.of("ref-a:1"),
                        List.of("4CH1-1.1"), HV)));
        Map<String, Object> section = Map.of(
                "views", Map.of("served_view_all_denominator", agg),
                "projection_census", Map.of("rows", 210));
        Map<String, Object> results = Map.of("spec_resolution_hv", section);
        String line = ChunkSpecHvResolution.reportLine(results, "ABSENT");
        assertTrue(line.contains("SCORED"));
        assertTrue(line.contains(String.valueOf(agg.get("spec_points_full_coverage_rate"))));
        assertFalse(line.equals("ABSENT"));
    }
}
