/**
 * Contextual Learning Assistant (CLA) — step-1 runtime slice (contract
 * {@code docs/CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md} §10.1).
 *
 * <p>The CLA is a conversational surface that operates IN THE CONTEXT OF WHAT
 * THE LEARNER IS CURRENTLY LOOKING AT, composing four existing verified
 * subsystems — educational retrieval, grounded tutor generation,
 * learner interaction memory, governed learner state — without replacing any
 * of them. It is subordinate to SyllabAI educational truth at every stage:</p>
 *
 * <ul>
 *   <li>context is SERVER-RESOLVED and fail-closed ({@link ClaContextResolver});</li>
 *   <li>tools are bounded, read-only, server-owned ({@link ClaToolRegistry});</li>
 *   <li>topic anchors are deterministic (the resolved context — never
 *       model-invented);</li>
 *   <li>generation reuses the Tutor's grounded stack with mode constraints;</li>
 *   <li>every exchange emits provenance-bearing interaction evidence
 *       ({@code ClaInteractionEvent}) — chat never mutates canonical KG,
 *       mastery, or validation state.</li>
 * </ul>
 *
 * <p>Step 1 serves the KG_TOPIC context kind with EXPLAIN/SUMMARIZE modes.
 * Attempt-aware HINT/CHECK with the answer-leakage gate (contract §7/§10.2),
 * further context kinds, and further read-only tools land in later steps,
 * each behind its own tests. No step bypasses contract §2–§8.</p>
 */
package com.syllabai.cla;
