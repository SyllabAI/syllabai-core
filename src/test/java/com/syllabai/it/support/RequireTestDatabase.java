package com.syllabai.it.support;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test-database guard (T-C04 r2 hardening, operator directive 2026-09-13):
 * the core test suite must never be able to touch the campaign database.
 *
 * <p>Registered globally via the JUnit Platform ServiceLoader mechanism and
 * enabled for every surefire/failsafe run through
 * {@code junit.jupiter.extensions.autodetection.enabled=true} (set in the
 * pom), this extension verifies — before each test class — that the
 * configured JDBC target is the dedicated, disposable test database
 * {@code syllabai_test} and <em>only</em> that database. Anything else — the
 * campaign DB, a staging DB, an unset target — fails the class immediately,
 * before any context boots or any connection opens: fail closed.</p>
 *
 * <p>Why the strict default: the main {@code application.yml} still defaults
 * {@code spring.datasource.url} to the campaign database (that default is
 * correct for the deployed app). Tests therefore MUST receive the test-database
 * URL explicitly — the pom does this for every maven run via
 * {@code spring.datasource.url}; a run that bypasses the pom (e.g. an IDE)
 * must set {@code -Dspring.datasource.url=jdbc:postgresql://127.0.0.1:5432/syllabai_test}
 * itself. Silence is never interpreted as safety.</p>
 *
 * <p>Integration tests running inside Testcontainers keep working: their
 * container connection is supplied by {@code @ServiceConnection} at the
 * connection-details layer, while this guard validates the configuration
 * property — which the pom points at the disposable test database.</p>
 */
public class RequireTestDatabase implements BeforeAllCallback {

    /** The one database the test suite is allowed to target. */
    public static final String TEST_DATABASE = "syllabai_test";

    /** Property the pom sets for every surefire/failsafe execution. */
    public static final String URL_PROPERTY = "spring.datasource.url";

    /** Verdicts are unit-tested — see DatabaseIsolationGuardTest. */
    public enum Verdict { OK, FAIL_CLOSED }

    /** Pure decision function: which database does this URL name, and is it the test DB? */
    public static Verdict verdictFor(String url) {
        if (url == null || url.isBlank()) {
            // no configured target — refuse to interpret silence as safety
            return Verdict.FAIL_CLOSED;
        }
        // jdbc URLs are opaque to java.net.URI (scheme:…), so parse the database
        // name positionally: everything after the last '/' up to '?'. A URL with
        // no database segment names nothing — fail closed.
        String db = url.strip();
        int slash = db.lastIndexOf('/');
        db = slash >= 0 ? db.substring(slash + 1) : "";
        int q = db.indexOf('?');
        if (q >= 0) {
            db = db.substring(0, q);
        }
        // The campaign database name is never acceptable, and neither is anything
        // that is not exactly the disposable test database.
        return TEST_DATABASE.equals(db) ? Verdict.OK : Verdict.FAIL_CLOSED;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (verdictFor(System.getProperty(URL_PROPERTY)) == Verdict.FAIL_CLOSED) {
            String target = context != null && context.getTestClass().isPresent()
                    ? context.getTestClass().get().getName() : "<unknown>";
            throw new IllegalStateException(
                    "TEST DATABASE GUARD: refusing to run '" + target
                            + "' — the configured JDBC target is not the disposable test"
                            + " database '" + TEST_DATABASE + "'. Set -D" + URL_PROPERTY
                            + "=jdbc:postgresql://127.0.0.1:5432/" + TEST_DATABASE
                            + " (the maven build sets this for every run). Campaign data"
                            + " must never be reachable from a test context: fail closed.");
        }
    }
}
