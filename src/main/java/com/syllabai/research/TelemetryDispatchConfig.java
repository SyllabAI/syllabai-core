package com.syllabai.research;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dispatch posture for research telemetry (audit M1, 2026-10-02): the
 * {@link TelemetryService} event handlers leave the serving path. Every
 * handler is {@code @Async} on the {@code telemetryExecutor} defined here, so
 * a Neon write jitter can no longer turn a fully-generated tutor answer (or a
 * completed marking run) into a 5xx, and the request thread no longer pays
 * the telemetry round-trips.
 *
 * <p>Two mutually exclusive beans share the {@code telemetryExecutor} name —
 * exactly one exists in any context, so there is no definition override:</p>
 *
 * <ul>
 *   <li>{@code async} (default, production): a bounded pool — telemetry
 *       writes run off-thread with backpressure, never unbounded growth.</li>
 *   <li>{@code sync} (the {@code it} profile pins
 *       {@code syllabai.telemetry.dispatch=sync}): an inline executor so flow
 *       ITs can assert telemetry rows deterministically right after the act —
 *       the pre-M1 dispatch, kept as an explicit test seam rather than by
 *       accident.</li>
 * </ul>
 *
 * <p>Semantic note, accepted deliberately: in async mode the listener's own
 * {@code @Transactional} runs on the pool thread, so a telemetry row is its
 * own committed transaction — it no longer rolls back with a domain
 * transaction that fails after publishing. For an append-only research record
 * that is the correct direction of failure: telemetry must never fail the
 * ask, and a row for an act whose surrounding transaction later rolled back
 * is still an honest record that the act was attempted.</p>
 */
@Configuration
@EnableAsync
public class TelemetryDispatchConfig {

    /** Qualifier the {@code @Async} telemetry listeners dispatch through. */
    public static final String EXECUTOR = "telemetryExecutor";

    @Bean(name = EXECUTOR)
    @ConditionalOnProperty(prefix = "syllabai.telemetry", name = "dispatch",
            havingValue = "async", matchIfMissing = true)
    public ThreadPoolTaskExecutor telemetryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("telemetry-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        // backpressure, not failure: if the pool AND its queue saturate, the
        // write runs inline on the publishing thread — where the listeners'
        // own guard still keeps telemetry from failing the ask
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean(name = EXECUTOR)
    @ConditionalOnProperty(prefix = "syllabai.telemetry", name = "dispatch",
            havingValue = "sync")
    public SyncTaskExecutor telemetrySyncExecutor() {
        // deterministic dispatch for tests: listeners run inline on the
        // publishing thread (the pre-M1 behavior) so flow ITs can assert
        // telemetry rows immediately after the act
        return new SyncTaskExecutor();
    }
}
