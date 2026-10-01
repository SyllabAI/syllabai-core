package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Integration test: V57 ING ingest-era placeholder topic-node removal (the
 * app-migration lane named by the 2026-10-01 bank-residue-closure closeout).
 *
 * <p>The ING-* knowledge_nodes are leftovers of ingest-era placeholder topic
 * generation. The production cleanup lane (2026-10-01) deleted the 47
 * unanchored nodes but had to retain 57: archived questions anchor them via
 * questions.primary_topic_node_id, an app-level reference under a NOT NULL
 * constraint a DB lane cannot lift (re-pointing archived questions would
 * fabricate provenance). V57 changes the anchor-column semantics: the column
 * becomes nullable, archived questions lose their ING anchors (honest
 * absence), and the placeholder nodes are deleted under fail-closed
 * reference re-checks.</p>
 *
 * <p>Four pins against real Postgres with the full Flyway chain:</p>
 * <ol>
 *   <li>a FRESH database lands in the post-migration shape: the anchor
 *       column is nullable and zero ING nodes exist;</li>
 *   <li>a PRE-MIGRATION state (ING nodes + archived questions anchored to
 *       them) is repaired in place: anchors nulled, nodes deleted, serving
 *       question rows untouched;</li>
 *   <li>the guards are not decorative: an ACTIVE question anchoring an ING
 *       node, or a KG edge referencing one, fails the boot loudly instead of
 *       partially deleting;</li>
 *   <li>a second application is a structural no-op (idempotent).</li>
 * </ol>
 *
 * <p>Raw JdbcTemplate is deliberate: this is a store-contract test — the
 * migration's anchor-column semantics and guards are the behaviors under
 * test, not any service API.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class V57IngNodeRemovalIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Order(1)
    @DisplayName("a fresh database lands in the post-migration shape: nullable anchor, zero ING nodes")
    void freshDatabaseLandsInPostMigrationShape() {
        Map<String, Object> col = jdbc.queryForMap("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'questions' AND column_name = 'primary_topic_node_id'
                """);
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(ingNodeCount()).isZero();
        assertThat(anchoredQuestionCount()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("V57 repairs a pre-migration state in place: archived anchors nulled, ING nodes deleted")
    void migrationRepairsAPreMigrationState() {
        // simulate the pre-migration state: 3 ING nodes, two archived questions
        // anchored to them (the serving surface never anchored an ING node)
        insertIngNode("ING-TEST-CHEM-01");
        insertIngNode("ING-TEST-CHEM-02");
        insertIngNode("ING-TEST-CHEM-03");
        UUID q1 = insertArchivedQuestion("v57-it-archived-1", "ING-TEST-CHEM-01");
        UUID q2 = insertArchivedQuestion("v57-it-archived-2", "ING-TEST-CHEM-02");

        assertThat(ingNodeCount()).isEqualTo(3);
        assertThat(anchoredQuestionCount()).isEqualTo(2);

        jdbc.execute(migrationSql());

        // archived questions survive with their anchors honestly absent;
        // the placeholder nodes are gone
        assertThat(jdbc.queryForObject("SELECT count(*) FROM questions WHERE id IN (?, ?)",
                Long.class, q1, q2)).isEqualTo(2L);
        assertThat(anchoredQuestionCount()).isZero();
        assertThat(ingNodeCount()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("the guards are not decorative: an active anchor or a KG edge reference fails loudly")
    void migrationFailsClosedOnUnexpectedReferences() {
        // drift 1: an ACTIVE question anchoring an ING node — the guard must
        // abort the boot instead of serving a question whose topic vanishes
        insertIngNode("ING-TEST-DRIFT-EDGE");
        jdbc.update("""
                UPDATE questions SET primary_topic_node_id = (SELECT id FROM knowledge_nodes WHERE code = 'ING-TEST-DRIFT-EDGE')
                WHERE id = ?
                """, insertActiveQuestion("v57-it-active-drift", "ING-TEST-DRIFT-EDGE"));
        assertThatThrownBy(this::runMigration)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("V57 guard");
        jdbc.update("UPDATE questions SET primary_topic_node_id = NULL WHERE external_ref = ?",
                "v57-it-active-drift");

        // drift 2: a KG edge referencing an ING node
        jdbc.update("""
                INSERT INTO knowledge_edges (id, source_node_id, target_node_id,
                        relation_type, strength, rationale, validation_status,
                        provenance, created_by, version, created_at)
                SELECT gen_random_uuid(), ing.id, t.id, 'RELATED_TO',
                        NULL, 'drift simulation (V57 IT fixture)', 'VALIDATED',
                        'drift:simulation', 'v57-it', 1, now()
                FROM knowledge_nodes ing, knowledge_nodes t
                WHERE ing.code = 'ING-TEST-DRIFT-EDGE'
                  AND t.id = (SELECT id FROM knowledge_nodes WHERE code NOT LIKE 'ING-%' LIMIT 1)
                """);
        assertThatThrownBy(this::runMigration)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("V57 guard");

        // the aborted statements left no partial deletion; clean up and the
        // database is healthy again (the migration applies cleanly)
        jdbc.update("""
                DELETE FROM knowledge_edges e
                USING knowledge_nodes s
                WHERE e.source_node_id = s.id AND s.code = 'ING-TEST-DRIFT-EDGE'
                  AND e.provenance = 'drift:simulation'
                """);
        jdbc.update("DELETE FROM knowledge_nodes WHERE code LIKE 'ING-TEST-%'");
        jdbc.execute(migrationSql());
        assertThat(ingNodeCount()).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("a second application is a structural no-op (idempotent)")
    void migrationIsIdempotent() {
        jdbc.execute(migrationSql());
        assertThat(ingNodeCount()).isZero();
        assertThat(anchoredQuestionCount()).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────

    private long ingNodeCount() {
        List<Long> counts = jdbc.queryForList(
                "SELECT count(*) FROM knowledge_nodes WHERE code LIKE 'ING-%'", Long.class);
        return counts.get(0);
    }

    private long anchoredQuestionCount() {
        List<Long> counts = jdbc.queryForList("""
                SELECT count(*) FROM questions q
                JOIN knowledge_nodes kn ON kn.id = q.primary_topic_node_id
                WHERE kn.code LIKE 'ING-%'
                """, Long.class);
        return counts.get(0);
    }

    private void insertIngNode(String code) {
        jdbc.update("""
                INSERT INTO knowledge_nodes (id, code, node_type, title, validation_status,
                        provenance, created_by, version, created_at)
                VALUES (gen_random_uuid(), ?, 'TOPIC', 'ING fixture topic', 'SUGGESTED',
                        'v57-it:fixture', 'v57-it', 1, now())
                """, code);
    }

    private UUID insertArchivedQuestion(String externalRef, String ingCode) {
        return insertQuestion(externalRef, ingCode, false);
    }

    private UUID insertActiveQuestion(String externalRef, String ingCode) {
        return insertQuestion(externalRef, ingCode, true);
    }

    private UUID insertQuestion(String externalRef, String ingCode, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO questions (id, external_ref, question_type, stem, marks, difficulty,
                        expected_time_seconds, primary_topic_node_id, provenance, active, version, created_at)
                SELECT ?, ?, 'STRUCTURED', 'V57 IT fixture question', 1, 3, 60,
                        (SELECT id FROM knowledge_nodes WHERE code = ?), 'PAST_PAPER', ?, 1, now()
                """, id, externalRef, ingCode, active);
        return id;
    }

    private void runMigration() {
        jdbc.execute(migrationSql());
    }

    /** The packaged V57 statement, comment lines stripped, exactly as Flyway runs it. */
    private static String migrationSql() {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ClassPathResource(
                "db/migration/V57__ing_node_removal.sql").getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.strip().startsWith("--")) {
                    sb.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }
}
