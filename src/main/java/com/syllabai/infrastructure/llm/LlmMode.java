package com.syllabai.infrastructure.llm;

/**
 * Routing mode for the LLM layer (ADR-023, {@code syllabai.llm.mode}).
 *
 * <ul>
 *   <li>{@link #PRODUCTION} — default: real providers register per configuration.</li>
 *   <li>{@link #TEST} — FAIL-CLOSED: real provider adapters are never constructed,
 *       regardless of which API keys are present in the environment. Requests for LLM
 *       generation fail loudly instead of silently spending quota; deterministic
 *       fake providers live in the test sourceset and are wired by the tests that
 *       need them.</li>
 *   <li>{@link #LIVE} — real providers for explicit live-verification runs; any test
 *       that reaches a real provider must additionally opt in via
 *       {@code LIVE_LLM_TESTS=explicit} (never enabled by default).</li>
 * </ul>
 */
public enum LlmMode {
    PRODUCTION,
    TEST,
    LIVE
}
