/**
 * recommendation module — learning-first Next Best Learning Action (Master Spec
 * §6.9, ADR-017, RECOMMENDATION_SYSTEM_ARCHITECTURE.md). Cycle-1 scope: the
 * deterministic rule-based baseline {@code nba-rules/v1} (F-092 minimal slice,
 * promoted by the 2026-09-08 finish-the-product directive); the learned ranking,
 * collaborative filtering and exploration machinery of the full architecture
 * remain Cycle 2+ experiments. No microservice — this is a package of
 * syllabai-core like every other domain module.
 */
package com.syllabai.recommendation;
