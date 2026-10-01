package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.teacher.ConceptGraphSeedService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the 2026-10-01 practical-endpoint retarget (V54 + the
 * re-pinned store snapshot). The 12 HUMAN_VALIDATED practical
 * REQUIRES_PREREQUISITE edges previously sourced at the ad-hoc practical-node
 * codes (4CH1-PR-01..11); they now source at the practicals' real spec
 * statements (4CH1-1.7C, 1.13, 1.36, 1.60C, 3.8, 3.15, 3.16) — the upstream
 * lockstep of the syllabai-hub mirror retarget (fix_pr_edges.py fd05d75 +
 * fix_pr_mirror.py; hub manifest docs/TC11_BATCH5_MANIFEST.md follow-up 1).
 *
 * <p>Three pins against real Postgres with the full Flyway chain:</p>
 * <ol>
 *   <li>a FRESH database seeds directly into the retargeted shape: zero
 *       prerequisite edges sourced at practical nodes, exactly 19 sourced at
 *       the 12 spec statements, seed provenance lines byte-stable
 *       (the store's provenance fields were deliberately untouched);</li>
 *   <li>a PRE-RETARGET seeded database (simulated by moving the 12 rows back to
 *       their ad-hoc sources with their original provenance) is repaired IN
 *       PLACE by the V54 statement — provenance/rationale/status byte-preserved,
 *       no duplicates — and a re-activation afterwards is a full structural
 *       no-op (the seed's identity + provenance contract holds across the
 *       migration, which is exactly why the store kept the provenance fields
 *       stable);</li>
 *   <li>the V54 guard is not decorative: a drifted database (a PR-sourced edge
 *       whose destination identity already exists) fails the boot loudly
 *       instead of partially moving rows.</li>
 * </ol>
 *
 * <p>Raw JdbcTemplate is deliberate: this is a store-contract test — the
 * migration and the seed's idempotent resolution are the behaviors under
 * test, not any service API.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class V54PracticalEndpointRetargetIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** (ad-hoc practical code, real spec-statement code, target concept code). */
    private static final String[][] OLD_STATE = {
            {"4CH1-PR-01", "4CH1-1.7C", "4CH1-CON-SATURATED-SOLUTION"},
            {"4CH1-PR-01", "4CH1-1.7C", "4CH1-CON-SOLUBILITY"},
            {"4CH1-PR-02", "4CH1-1.13", "4CH1-CON-CHROMATOGRAPHY"},
            {"4CH1-PR-02", "4CH1-1.13", "4CH1-CON-RF-VALUE"},
            {"4CH1-PR-03", "4CH1-1.36", "4CH1-CON-EXP-FORMULA-DEDUCTION"},
            {"4CH1-PR-04", "4CH1-1.60C", "4CH1-CON-AQUEOUS-DISCHARGE"},
            {"4CH1-PR-04", "4CH1-1.60C", "4CH1-CON-ELECTROLYSIS"},
            {"4CH1-PR-09", "4CH1-3.8", "4CH1-CON-CALORIMETRY"},
            {"4CH1-PR-10", "4CH1-3.15", "4CH1-CON-RATE-EXPERIMENTS"},
            {"4CH1-PR-10", "4CH1-3.15", "4CH1-CON-RATE-FACTORS"},
            {"4CH1-PR-11", "4CH1-3.16", "4CH1-CON-CATALYST"},
            {"4CH1-PR-11", "4CH1-3.16", "4CH1-CON-RATE-EXPERIMENTS"},
    };

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConceptGraphSeedService seed;

    @Test
    @Order(1)
    @DisplayName("a fresh database seeds directly into the retargeted shape with byte-stable provenance")
    void freshSeedCarriesSpecStatementSourcedPracticalEdges() {
        ConceptGraphSeedService.SeedSummary summary = seed.activate(UUID.randomUUID());
        assertThat(summary.validatedSemanticEdges()).isEqualTo(272);
        assertThat(summary.edgesCreated()).isEqualTo(4 + 28 + 182 + 12 + 211 + 272);

        assertThat(prSourcedPrerequisiteEdges()).isZero();
        assertThat(specStatementSourcedPrerequisiteEdges()).isEqualTo(19);

        // the seed-built provenance line is byte-identical to the pre-retarget
        // store's (the retarget deliberately did not touch provenance fields)
        assertThat(provenanceOf("4CH1-1.7C", "4CH1-CON-SATURATED-SOLUTION"))
                .isEqualTo("t-c11:settled|pass:c11-s16-batch-1"
                        + "|method:USED_WITHOUT_RETEACHING"
                        + "|validated_by:operator|date:2026-09-12");
    }

    @Test
    @Order(2)
    @DisplayName("V54 repairs a pre-retarget seeded database in place; re-activation is a full structural no-op")
    void migrationRepairsAPreRetargetSeededDatabase() {
        // the spot row's full state BEFORE the simulation — the migration must
        // leave every column except the source byte-identical
        Map<String, Object> spotBefore = jdbc.queryForMap("""
                SELECT e.provenance, e.rationale, e.validation_status, e.created_by
                FROM knowledge_edges e
                JOIN knowledge_nodes s ON s.id = e.source_node_id
                JOIN knowledge_nodes t ON t.id = e.target_node_id
                WHERE s.code = '4CH1-1.7C' AND t.code = '4CH1-CON-SATURATED-SOLUTION'
                  AND e.relation_type = 'REQUIRES_PREREQUISITE'
                """);
        assertThat(spotBefore.get("provenance")).isEqualTo(
                "t-c11:settled|pass:c11-s16-batch-1|method:USED_WITHOUT_RETEACHING"
                        + "|validated_by:operator|date:2026-09-12");
        assertThat(spotBefore.get("validation_status")).isEqualTo("VALIDATED");

        // simulate the pre-retarget seeded state: move the 12 rows back to their
        // ad-hoc sources, keeping the exact rows (provenance, rationale, status)
        Map<String, String> provenanceBefore = new HashMap<>();
        for (String[] t : OLD_STATE) {
            provenanceBefore.put(t[2] + "|" + t[1], provenanceOf(t[1], t[2]));
            jdbc.update("""
                    UPDATE knowledge_edges e
                    SET source_node_id = (SELECT id FROM knowledge_nodes WHERE code = ?)
                    WHERE source_node_id = (SELECT id FROM knowledge_nodes WHERE code = ?)
                      AND target_node_id = (SELECT id FROM knowledge_nodes WHERE code = ?)
                      AND relation_type = 'REQUIRES_PREREQUISITE'
                    """, t[0], t[1], t[2]);
        }
        assertThat(prSourcedPrerequisiteEdges()).isEqualTo(12);

        // the migration statement, exactly as Flyway applies it
        jdbc.execute(migrationSql());

        // moved IN PLACE: no ad-hoc source remains, no duplicate was created,
        // and every row came back with its original provenance byte-for-byte
        assertThat(prSourcedPrerequisiteEdges()).isZero();
        assertThat(specStatementSourcedPrerequisiteEdges()).isEqualTo(19);
        for (String[] t : OLD_STATE) {
            assertThat(provenanceOf(t[1], t[2]))
                    .as("provenance of %s -> %s", t[1], t[2])
                    .isEqualTo(provenanceBefore.get(t[2] + "|" + t[1]));
        }
        // the move touched ONLY the source node: provenance, rationale, status
        // and created_by are byte-identical to the pre-simulation seeded row
        Map<String, Object> spotAfter = jdbc.queryForMap("""
                SELECT e.provenance, e.rationale, e.validation_status, e.created_by
                FROM knowledge_edges e
                JOIN knowledge_nodes s ON s.id = e.source_node_id
                JOIN knowledge_nodes t ON t.id = e.target_node_id
                WHERE s.code = '4CH1-1.7C' AND t.code = '4CH1-CON-SATURATED-SOLUTION'
                  AND e.relation_type = 'REQUIRES_PREREQUISITE'
                """);
        assertThat(spotAfter).isEqualTo(spotBefore);

        // the lockstep proof: with the re-pinned snapshot, re-activation resolves
        // every migrated row by identity + provenance — zero new rows, no conflict
        ConceptGraphSeedService.SeedSummary reactivation = seed.activate(UUID.randomUUID());
        assertThat(reactivation.nodesCreated()).isZero();
        assertThat(reactivation.edgesCreated()).isZero();
        assertThat(reactivation.alreadyActive()).isTrue();

        // idempotent: a second application is a structural no-op
        jdbc.execute(migrationSql());
        assertThat(prSourcedPrerequisiteEdges()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("the guard is not decorative: a drifted database fails loudly instead of partially moving rows")
    void migrationFailsClosedOnDrift() {
        // drift: an ad-hoc-sourced edge whose destination identity already exists
        jdbc.update("""
                INSERT INTO knowledge_edges (id, source_node_id, target_node_id,
                        relation_type, strength, rationale, validation_status,
                        provenance, created_by, version, created_at)
                SELECT gen_random_uuid(), pr.id, t.id, 'REQUIRES_PREREQUISITE',
                        NULL, 'drift simulation (V54 IT fixture)', 'VALIDATED',
                        'drift:simulation', 'v54-it', 1, now()
                FROM knowledge_nodes pr, knowledge_nodes t
                WHERE pr.code = '4CH1-PR-01'
                  AND t.code = '4CH1-CON-SATURATED-SOLUTION'
                """);

        assertThatThrownBy(this::runMigration)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("V54 retarget");

        // the aborted statement left no partial move; clean up and the database
        // is healthy again (the migration applies as a structural no-op)
        jdbc.update("""
                DELETE FROM knowledge_edges e
                USING knowledge_nodes s
                WHERE e.source_node_id = s.id AND s.code = '4CH1-PR-01'
                  AND e.provenance = 'drift:simulation'
                """);
        jdbc.execute(migrationSql());
        assertThat(prSourcedPrerequisiteEdges()).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────

    private int prSourcedPrerequisiteEdges() {
        List<Long> counts = jdbc.queryForList("""
                SELECT COUNT(*) FROM knowledge_edges e
                JOIN knowledge_nodes s ON s.id = e.source_node_id
                WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
                  AND s.code LIKE '4CH1-PR-%'
                """, Long.class);
        return counts.get(0).intValue();
    }

    private int specStatementSourcedPrerequisiteEdges() {
        List<Long> counts = jdbc.queryForList("""
                SELECT COUNT(*) FROM knowledge_edges e
                JOIN knowledge_nodes s ON s.id = e.source_node_id
                WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
                  AND s.code IN ('4CH1-1.7C', '4CH1-1.13', '4CH1-1.36', '4CH1-1.60C',
                                 '4CH1-2.14', '4CH1-2.21', '4CH1-2.42', '4CH1-2.43C',
                                 '4CH1-3.8', '4CH1-3.15', '4CH1-3.16', '4CH1-4.43C')
                """, Long.class);
        return counts.get(0).intValue();
    }

    private String provenanceOf(String sourceCode, String targetCode) {
        return jdbc.queryForObject("""
                SELECT e.provenance FROM knowledge_edges e
                JOIN knowledge_nodes s ON s.id = e.source_node_id
                JOIN knowledge_nodes t ON t.id = e.target_node_id
                WHERE s.code = ? AND t.code = ?
                  AND e.relation_type = 'REQUIRES_PREREQUISITE'
                """, String.class, sourceCode, targetCode);
    }

    private void runMigration() {
        jdbc.execute(migrationSql());
    }

    /** The packaged V54 statement, comment lines stripped, exactly as Flyway runs it. */
    private static String migrationSql() {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ClassPathResource(
                "db/migration/V54__retarget_practical_edge_endpoints.sql").getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().startsWith("--")) {
                    sb.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the packaged V54 statement", e);
        }
        return sb.toString();
    }
}
