package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: deep-audit 2026-09-28 M6 — FK closure for learner-owned
 * rows. Eight learner_id columns (attempts, skill_states, misconception_states,
 * review_schedules, telemetry_events, tutor_topic_engagements,
 * learner_self_marks, tutor_sessions) carried {@code NOT NULL} with no FOREIGN
 * KEY to users(id): the schema did not enforce the deletion posture its own
 * migration headers claim (V42: "Deletion follows the learner account
 * (cascade), same as every learner-owned row"). V45 adds the eight
 * {@code <table>_learner_id_fkey} constraints with ON DELETE CASCADE.
 *
 * <p>Three pins here, against real Postgres with the full Flyway chain:</p>
 * <ol>
 *   <li>all eight constraints EXIST with delete rule CASCADE (drift detector —
 *       catches a dropped, renamed, or RESTRICT-ified constraint);</li>
 *   <li>the cascade actually follows the learner account: one user, one row in
 *       each of the eight tables (including the full question_version → part →
 *       attempt → answer chain learner_self_marks hangs off), delete the user,
 *       all eight rows are gone;</li>
 *   <li>the constraint is not decorative: an attempt row with an unknown
 *       learner_id is rejected by the database itself.</li>
 * </ol>
 *
 * <p>Raw JdbcTemplate is deliberate: this is a schema-contract test, not a
 * service-behavior test — the service layer never writes orphanable ids (it
 * resolves learner ids from the JWT), which is exactly why the missing FKs
 * stayed invisible to every service-level test.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class LearnerOwnedRowFkIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** V6 seed: WCH11-T1.1 topic node (FK target for node_id columns). */
    private static final UUID TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V6 seed: MIS-T1.1-01 misconception node. */
    private static final UUID MIS_T1_1_01 =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    /** V7 seed: SEED-WCH11-001 MCQ (FK target for attempts.question_id). */
    private static final UUID SEED_MCQ =
            UUID.fromString("40000000-0000-0000-0000-000000000001");

    /** The eight V45 constraints, table → constraint name (Postgres default). */
    private static final Map<String, String> V45_FKS = Map.of(
            "attempts", "attempts_learner_id_fkey",
            "skill_states", "skill_states_learner_id_fkey",
            "misconception_states", "misconception_states_learner_id_fkey",
            "review_schedules", "review_schedules_learner_id_fkey",
            "telemetry_events", "telemetry_events_learner_id_fkey",
            "tutor_topic_engagements", "tutor_topic_engagements_learner_id_fkey",
            "learner_self_marks", "learner_self_marks_learner_id_fkey",
            "tutor_sessions", "tutor_sessions_learner_id_fkey");

    /** Every learner-owned table the cascade must reach. */
    private static final List<String> LEARNER_TABLES = List.of(
            "attempts", "skill_states", "misconception_states", "review_schedules",
            "telemetry_events", "tutor_topic_engagements", "learner_self_marks",
            "tutor_sessions");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("M6: all eight learner-owned tables carry a CASCADE FK to users(id)")
    void allEightLearnerOwnedTablesCarryCascadeFkToUsers() {
        Map<String, Map<String, String>> byTable = userFksByTable();
        V45_FKS.forEach((table, constraint) -> {
            Map<String, String> fk = byTable.get(table);
            assertThat(fk)
                    .as("table %s must have a FK to users(id)", table)
                    .isNotNull();
            assertThat(fk.get("constraint"))
                    .as("table %s constraint name", table)
                    .isEqualTo(constraint);
            assertThat(fk.get("delete_rule"))
                    .as("table %s delete rule", table)
                    .isEqualTo("CASCADE");
        });
    }

    @Test
    @DisplayName("M6: deleting the learner account cascades to all eight learner-owned rows")
    void cascadeFollowsTheLearnerAccount() {
        UUID learner = seedLearnerWithOneRowInEveryTable();

        LEARNER_TABLES.forEach(table -> assertThat(
                countFor(learner, table))
                .as("%s rows for the learner before delete", table)
                .isEqualTo(1));

        jdbc.update("DELETE FROM users WHERE id = ?", learner);

        LEARNER_TABLES.forEach(table -> assertThat(
                countFor(learner, table))
                .as("%s rows for the learner after user delete", table)
                .isZero());
    }

    @Test
    @DisplayName("M6: the FK is enforced — an attempt with an unknown learner_id is rejected")
    void fkRejectsUnknownLearner() {
        UUID ghost = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO attempts (id, learner_id, question_id, correct, "
                        + "response_time_ms, provenance, created_at) "
                        + "VALUES (?, ?, ?, TRUE, 1000, 'IT_STUB', now())",
                UUID.randomUUID(), ghost, SEED_MCQ))
                .as("attempts.learner_id must be FK-enforced against users(id)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** table → {constraint, delete_rule} for every FK pointing at users(id). */
    private Map<String, Map<String, String>> userFksByTable() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tc.table_name AS tbl, tc.constraint_name AS cname, "
                        + "rc.delete_rule AS drule "
                        + "FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.referential_constraints rc "
                        + "  ON tc.constraint_name = rc.constraint_name "
                        + " AND tc.table_schema = rc.constraint_schema "
                        + "JOIN information_schema.table_constraints uc "
                        + "  ON rc.unique_constraint_name = uc.constraint_name "
                        + " AND uc.table_schema = uc.constraint_schema "
                        + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                        + "  AND tc.table_schema = 'public' "
                        + "  AND uc.table_name = 'users'");
        Map<String, Map<String, String>> byTable = new HashMap<>();
        for (Map<String, Object> row : rows) {
            byTable.put(String.valueOf(row.get("tbl")), Map.of(
                    "constraint", String.valueOf(row.get("cname")),
                    "delete_rule", String.valueOf(row.get("drule"))));
        }
        return byTable;
    }

    /** One user + exactly one row in each of the eight learner-owned tables. */
    private UUID seedLearnerWithOneRowInEveryTable() {
        UUID learner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name, "
                        + "enabled, created_at) VALUES (?, ?, 'it-stub-hash', "
                        + "'M6 Cascade IT', TRUE, now())",
                learner, "m6-cascade-" + learner + "@it.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'STUDENT')",
                learner);

        // chain: question_version → part → attempt → answer → learner_self_mark
        // (learner_self_marks.answer_id is FK-validated, so the full chain is
        // required to place its one row).
        UUID version = UUID.randomUUID();
        jdbc.update("INSERT INTO question_versions (id, question_id, version, "
                        + "stem, marks, difficulty, expected_time_seconds, created_at) "
                        + "VALUES (?, ?, 99, 'M6 IT stub stem', 1, 1, 30, now())",
                version, SEED_MCQ);
        UUID part = UUID.randomUUID();
        jdbc.update("INSERT INTO question_parts (id, question_version_id, label, "
                        + "prompt, marks, ordering, created_at) "
                        + "VALUES (?, ?, 'M6IT', 'M6 IT stub part', 1, 99, now())",
                part, version);
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO attempts (id, learner_id, question_id, correct, "
                        + "response_time_ms, provenance, created_at) "
                        + "VALUES (?, ?, ?, TRUE, 1234, 'IT_STUB', now())",
                attempt, learner, SEED_MCQ);
        UUID answer = UUID.randomUUID();
        jdbc.update("INSERT INTO answers (id, attempt_id, question_part_id, "
                        + "marking_state, created_at) "
                        + "VALUES (?, ?, ?, 'PENDING', now())",
                answer, attempt, part);
        jdbc.update("INSERT INTO learner_self_marks (id, answer_id, learner_id, "
                        + "marks_awarded, created_at) VALUES (?, ?, ?, 1, now())",
                UUID.randomUUID(), answer, learner);

        jdbc.update("INSERT INTO skill_states (id, learner_id, node_id, mastery, "
                        + "attempts, correct_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0.5, 1, 1, now(), now())",
                UUID.randomUUID(), learner, TOPIC_T1_1);
        jdbc.update("INSERT INTO misconception_states (id, learner_id, "
                        + "misconception_node_id, probability, evidence_count, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 0.4, 1, now(), now())",
                UUID.randomUUID(), learner, MIS_T1_1_01);
        jdbc.update("INSERT INTO review_schedules (id, learner_id, node_id, "
                        + "due_at, created_at) VALUES (?, ?, ?, now() + interval '1 day', now())",
                UUID.randomUUID(), learner, TOPIC_T1_1);
        jdbc.update("INSERT INTO telemetry_events (id, learner_id, event_type, "
                        + "payload, occurred_at) VALUES (?, ?, 'ATTEMPT_SUBMITTED', "
                        + "'{}'::jsonb, now())",
                UUID.randomUUID(), learner);
        jdbc.update("INSERT INTO tutor_topic_engagements (id, learner_id, node_id, "
                        + "occurred_at, evidence_count, refused, created_at) "
                        + "VALUES (?, ?, ?, now(), 1, FALSE, now())",
                UUID.randomUUID(), learner, TOPIC_T1_1);
        jdbc.update("INSERT INTO tutor_sessions (id, learner_id, created_at, "
                        + "last_active_at) VALUES (?, ?, now(), now())",
                UUID.randomUUID(), learner);

        return learner;
    }

    private long countFor(UUID learner, String table) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE learner_id = ?",
                Long.class, learner);
        return n == null ? -1 : n;
    }
}
