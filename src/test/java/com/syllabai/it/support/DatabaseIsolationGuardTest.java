package com.syllabai.it.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Database-isolation regression test (operator directive 2026-09-13, item 3):
 * pins the isolation contract that keeps the campaign data safe from the test
 * suite.
 *
 * <p>Three layers are proven here:</p>
 * <ol>
 *   <li>the guard's decision function rejects every target that is not the
 *       disposable {@code syllabai_test} — including the campaign database
 *       itself, lookalikes, and an UNSET target (missing identity → fail
 *       closed);</li>
 *   <li>the guard actually aborts a test class (its {@code beforeAll} throws)
 *       when pointed at the campaign database;</li>
 *   <li>live check (skipped when no local Postgres is running): the test
 *       database really is {@code syllabai_test} and it never claims campaign
 *       identity — the {@code campaign_db_identity} row stays unclaimed, which
 *       is the fact destructive tooling's fail-closed preflight depends on.</li>
 * </ol>
 *
 * <p>The end-to-end proof that a FULL suite run leaves the campaign database
 * byte-identical lives in {@code scripts/run_tests_isolated.sh} (snapshot →
 * {@code mvn test} → re-snapshot → diff); this in-suite test pins the
 * mechanism that makes that proof hold.</p>
 */
class DatabaseIsolationGuardTest {

    private static final String CAMPAIGN_URL =
            "jdbc:postgresql://localhost:5432/syllabai";
    private static final String TEST_URL =
            "jdbc:postgresql://127.0.0.1:5432/syllabai_test";

    @Test
    @DisplayName("guard verdicts: only syllabai_test is acceptable — everything else fails closed")
    void guardVerdicts() {
        assertThat(RequireTestDatabase.verdictFor(TEST_URL))
                .isEqualTo(RequireTestDatabase.Verdict.OK);

        // the campaign database — the whole point of the guard
        assertThat(RequireTestDatabase.verdictFor(CAMPAIGN_URL))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
        // lookalikes and partial names are not waved through
        assertThat(RequireTestDatabase.verdictFor("jdbc:postgresql://127.0.0.1:5432/syllabai_staging"))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
        assertThat(RequireTestDatabase.verdictFor("jdbc:postgresql://127.0.0.1:5432/syllabaitest"))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
        // missing identity → fail closed: an unset target is never "safe"
        assertThat(RequireTestDatabase.verdictFor(null))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
        assertThat(RequireTestDatabase.verdictFor("   "))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
        assertThat(RequireTestDatabase.verdictFor("not-a-jdbc-url"))
                .isEqualTo(RequireTestDatabase.Verdict.FAIL_CLOSED);
    }

    @Test
    @DisplayName("the guard aborts a test class pointed at the campaign database")
    void guardAbortsOnCampaignTarget() {
        String original = System.getProperty(RequireTestDatabase.URL_PROPERTY);
        System.setProperty(RequireTestDatabase.URL_PROPERTY, CAMPAIGN_URL);
        try {
            IllegalStateException aborted = assertThrows(IllegalStateException.class,
                    () -> new RequireTestDatabase().beforeAll(null));
            assertThat(aborted.getMessage()).contains("TEST DATABASE GUARD");
            assertThat(aborted.getMessage()).contains("syllabai_test");
        } finally {
            if (original == null) {
                System.clearProperty(RequireTestDatabase.URL_PROPERTY);
            } else {
                System.setProperty(RequireTestDatabase.URL_PROPERTY, original);
            }
        }
    }

    @Test
    @DisplayName("live: the test DB is syllabai_test and never claims campaign identity")
    void testDatabaseIsIsolatedAndUnclaimed() throws Exception {
        String url = System.getProperty(RequireTestDatabase.URL_PROPERTY, TEST_URL);
        assumeTrue(RequireTestDatabase.verdictFor(url) == RequireTestDatabase.Verdict.OK,
                "guard must accept the configured test DB before it can be inspected");

        try (Connection connection = DriverManager.getConnection(url, "syllabai", "syllabai")) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(RequireTestDatabase.TEST_DATABASE);
            }
            // tests never claim campaign identity — V15's table must be either
            // absent (migration-only DB) or empty / UNCLAIMED. Destructive
            // tooling treats both as "not the campaign DB" → fail closed.
            try (Statement st = connection.createStatement()) {
                ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM campaign_db_identity"
                                + " WHERE campaign_label <> 'UNCLAIMED'");
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        } catch (java.sql.SQLException unreachable) {
            assumeTrue(false, "no local test database at " + url + " — live check skipped");
        }
    }
}
