package com.syllabai.infrastructure.llm;

/**
 * How hard a reasoning-capable model should think before answering — the
 * provider-agnostic knob behind {@link LlmRequest#reasoningEffort()} (null =
 * provider default, the pre-knob behavior for every existing caller).
 *
 * <p>Wire mapping is per provider family, applied inside
 * {@code LlmChainConfig}'s runtime-options factories — the port level never
 * sees provider detail:</p>
 * <ul>
 *   <li>OpenAI-compatible (Groq, OpenRouter): {@code reasoning_effort} with
 *       the lowercase wire name ({@code minimal}/{@code low}/{@code medium}/
 *       {@code high}).</li>
 *   <li>Google GenAI (Gemini): {@code thinkingLevel} — the Spring AI enum
 *       names match this one exactly (MINIMAL/LOW/MEDIUM/HIGH).</li>
 * </ul>
 *
 * <p>A model that rejects a requested level fails the provider call honestly
 * (classified failure → failover/cooldown) — never silently downgraded here:
 * a knob that quietly changes value on one leg of the chain would make the
 * three legs disagree about the answer they are computing. Callers choose
 * levels the whole chain can serve.</p>
 */
public enum LlmReasoningEffort {
    /** near-zero thinking tokens (Gemini thinkingLevel MINIMAL / OpenAI "minimal") */
    MINIMAL,
    /** bounded quick thinking — extraction/rubric-shaped work (SmartMark marking) */
    LOW,
    /** provider default in spirit — balanced reasoning */
    MEDIUM,
    /** extensive thinking — hard multi-step work (not used by any caller yet) */
    HIGH;

    /** The lowercase OpenAI-compatible {@code reasoning_effort} wire value. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
