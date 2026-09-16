package com.syllabai.infrastructure.llm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reusable deterministic fake {@link LlmProvider} for tests (ADR-023 Slice B) — the
 * single test fixture for the §26.1 chain behaviour contract. NOT a production bean:
 * it lives in the test sourceset and is wired explicitly by the tests that need it.
 *
 * <p>Capabilities:</p>
 * <ul>
 *   <li><strong>Deterministic response</strong> — a configured response is returned
 *       unchanged (default: {@code "answer from <name>"} with model
 *       {@code fake-model}).</li>
 *   <li><strong>Configurable failure</strong> — script a {@link LlmFailureClass}
 *       (adapter-style classified failure) or a raw {@link RuntimeException}
 *       (mirrors SpringAiChatModelAdapter's message contract). Failures feed the
 *       real {@link LlmProviderHealth}, so cooldown/budget behaviour is exercised
 *       exactly as in production.</li>
 *   <li><strong>Call recording</strong> — invocation count, requested model,
 *       experiment id, sampling options and cross-provider invocation order.
 *       Prompt TEXT is deliberately not recorded (learner data must not accumulate
 *       in fixtures); tests that need the full request use {@link #lastRequest()}.</li>
 * </ul>
 *
 * <p>Usage:
 * {@snippet :
 * FakeLlmProvider groq = FakeLlmProvider.named("groq").respondsWith("42").recordOrderInto(order);
 * FakeLlmProvider gemini = FakeLlmProvider.named("gemini").alwaysFails(LlmFailureClass.RATE_LIMITED);
 * }
 */
public final class FakeLlmProvider implements LlmProvider {

    /** One recorded invocation — request metadata only, no prompt content. */
    public record Call(int index, String providerName, String model, String experimentId,
                       Double temperature, Integer maxTokens, Instant at) {
    }

    private final String name;
    private final boolean configured;
    private final LlmProviderHealth health;
    private final List<Call> calls = new ArrayList<>();
    /** Failure queue: each entry is consumed by one generate() call. */
    private final List<LlmProviderException> scriptedFailures = new ArrayList<>();
    /** Failure thrown when no scripted failure remains (null = succeed). */
    private LlmProviderException defaultFailure;
    private LlmResponse response;
    private List<String> invocationOrder;
    private LlmRequest lastRequest;

    private FakeLlmProvider(String name, boolean configured, int dailyBudget) {
        this.name = name;
        this.configured = configured;
        this.health = new LlmProviderHealth(configured, configured, 3, 60, dailyBudget, null);
        this.response = new LlmResponse("answer from " + name, name, "fake-model", 5, 10, 10);
    }

    /** A configured, succeeding provider with the given name (no local budget). */
    public static FakeLlmProvider named(String name) {
        return new FakeLlmProvider(name, true, 0);
    }

    /** A configured provider with a configured LOCAL daily budget (ADR-023 tests). */
    public static FakeLlmProvider named(String name, int dailyBudget) {
        return new FakeLlmProvider(name, true, dailyBudget);
    }

    /** An unconfigured provider — the chain skips it silently. */
    public static FakeLlmProvider unconfigured(String name) {
        return new FakeLlmProvider(name, false, 0);
    }

    // ── scripting ───────────────────────────────────────────────────────────────

    /** Plain-text success response (provider/model identity default to this fake). */
    public FakeLlmProvider respondsWith(String text) {
        this.response = new LlmResponse(text, name, "fake-model", 5, 10, 10);
        return this;
    }

    /** Full success response — for tests asserting provider/model identity. */
    public FakeLlmProvider respondsWith(LlmResponse response) {
        this.response = response;
        return this;
    }

    /** Classified failure thrown on EVERY generate() (adapter-style message contract). */
    public FakeLlmProvider alwaysFails(LlmFailureClass failureClass) {
        this.defaultFailure = classifiedFailure(failureClass, "scripted " + failureClass);
        return this;
    }

    /** Classified failure with a custom detail message. */
    public FakeLlmProvider alwaysFails(LlmFailureClass failureClass, String message) {
        this.defaultFailure = classifiedFailure(failureClass, message);
        return this;
    }

    /** Raw-cause failure thrown on EVERY generate() — mirrors the adapter message. */
    public FakeLlmProvider alwaysFails(RuntimeException cause) {
        this.defaultFailure = mirroredFailure(cause);
        return this;
    }

    /** Queue {@code times} classified failures, then fall back to the default script. */
    public FakeLlmProvider failsNext(int times, LlmFailureClass failureClass) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.add(classifiedFailure(failureClass, "scripted " + failureClass));
        }
        return this;
    }

    private LlmProviderException classifiedFailure(LlmFailureClass failureClass, String message) {
        return new LlmProviderException(name,
                "generation failed (" + failureClass + ": " + message + ")", null, failureClass);
    }

    private LlmProviderException mirroredFailure(RuntimeException cause) {
        // mirror SpringAiChatModelAdapter's message contract: the cause class+message
        // travel inside the LlmProviderException message so the chain's aggregate
        // error names the real per-provider cause
        return new LlmProviderException(name,
                "generation failed (" + cause.getClass().getSimpleName() + ": "
                        + cause.getMessage() + ")",
                cause, LlmProviderFailureClassifier.classify(cause));
    }

    // ── recording ───────────────────────────────────────────────────────────────

    /** Record every invocation into a shared list → cross-provider invocation order. */
    public FakeLlmProvider recordOrderInto(List<String> invocationOrder) {
        this.invocationOrder = invocationOrder;
        return this;
    }

    /** Recorded invocations (metadata only — no prompt content). */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public int callCount() {
        return calls.size();
    }

    public Call lastCall() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    /** Full last request — for the few tests that genuinely need prompt content. */
    public LlmRequest lastRequest() {
        return lastRequest;
    }

    /** Models requested across all invocations, in order. */
    public List<String> requestedModels() {
        return calls.stream().map(Call::model).collect(Collectors.toList());
    }

    // ── LlmProvider ─────────────────────────────────────────────────────────────

    @Override public String name() {
        return name;
    }

    @Override public boolean available() {
        // mirrors SpringAiChatModelAdapter: configured, not cooling down, budget left
        return configured && !health.inCooldown() && !health.budgetExhausted();
    }

    @Override public LlmResponse generate(LlmRequest request) {
        lastRequest = request;
        calls.add(new Call(calls.size() + 1, name, request.model(), request.experimentId(),
                request.temperature(), request.maxTokens(), Instant.now()));
        if (invocationOrder != null) {
            invocationOrder.add(name);
        }
        LlmProviderException failure =
                scriptedFailures.isEmpty() ? defaultFailure : scriptedFailures.remove(0);
        if (failure != null) {
            health.recordFailure(failure.getMessage(), failure.failureClass());
            throw failure;
        }
        health.recordSuccess();
        return response;
    }

    @Override public LlmProviderHealth health() {
        return health;
    }
}
