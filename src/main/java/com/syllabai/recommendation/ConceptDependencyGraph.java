package com.syllabai.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settled T-C11 concept/prerequisite/misconception graph as a structured
 * dependency layer for the next-best-action engine (ADR-017 nba-rules/v1.1).
 *
 * <p><strong>What this object is:</strong> an immutable, read-only view of the
 * HUMAN_VALIDATED <em>semantic</em> relationships of the settled 4CH1 concept
 * graph (113 nodes / 275 edges / 153 HUMAN_VALIDATED at close of Batch 4,
 * 2026-09-13). It joins the runtime knowledge graph by <em>node code</em> — the
 * stable identity both stores guarantee (the runtime KG's
 * {@code uq_knowledge_node_code} and the T-C11 store's concept codes).</p>
 *
 * <p><strong>What this object is not:</strong> it is not learner state. Edges
 * here are curriculum-structure facts, never mastery evidence — a concept
 * existing in this graph says nothing about any learner. It is also not the
 * runtime KG: PART_OF structure (concept→spec-point) is deliberately excluded
 * because curriculum anchoring stays the authoritative KG's job; only the
 * semantic dependency relations are carried. The three frozen pilot HOLD
 * (SUGGESTED) edges and the two REVIEW_REQUIRED edges are excluded by
 * construction — the factory accepts raw edges with validation statuses and
 * keeps <em>only</em> {@code HUMAN_VALIDATED} ones, so a non-validated
 * relationship cannot enter this layer at all (T-C11 authoritative-evidence
 * rule: held/unvalidated edges must never drive authoritative
 * recommendations).</p>
 *
 * <p>Instances are built either by {@link ConceptDependencyGraphLoader} (the
 * packaged settled snapshot) or directly by tests with fixture edges; both go
 * through the same fail-closed factory, so there is exactly one filtering and
 * validation point.</p>
 */
public final class ConceptDependencyGraph {

    /** Semantic relation kinds of the settled T-C11 store (PART_OF excluded — KG owns structure). */
    public enum SemanticRelation {
        REQUIRES_PREREQUISITE,
        REMEDIATED_BY,
        EXPLAINED_BY,
        RELATED_TO,
        COMMONLY_CONFUSED_WITH,
        WRONG_ANSWER_PATTERN,
        MISCONCEPTION_OF
    }

    /** A raw edge as parsed from the store: endpoints are T-C11 concept codes. */
    public record RawEdge(String source, String relation, String target, String validationStatus) {
    }

    /** A validated dependency edge: {@code source relation target}, all codes. */
    public record Edge(String source, SemanticRelation relation, String target) {
    }

    private final Map<SemanticRelation, List<Edge>> byRelation;
    private final int validatedEdgeCount;

    private ConceptDependencyGraph(Map<SemanticRelation, List<Edge>> byRelation) {
        this.byRelation = byRelation;
        this.validatedEdgeCount = byRelation.values().stream().mapToInt(List::size).sum();
    }

    /** The empty graph — no applicable dependency (the honest default for absent input). */
    public static ConceptDependencyGraph empty() {
        return new ConceptDependencyGraph(Map.of());
    }

    /**
     * Fail-closed factory: keeps <em>only</em> HUMAN_VALIDATED edges (Case-D
     * exclusion of held/REVIEW_REQUIRED relationships happens here and nowhere
     * else), verifies every relation is a known semantic kind, every endpoint is
     * a known node code, and no validated edge is duplicated — any violation is
     * a defect in the graph source, not something to silently tolerate.
     *
     * @param rawEdges       edges exactly as stored (any validation status)
     * @param knownNodeCodes node codes the endpoints must resolve against
     */
    public static ConceptDependencyGraph of(List<RawEdge> rawEdges, Set<String> knownNodeCodes) {
        Map<SemanticRelation, List<Edge>> collected = new EnumMap<>(SemanticRelation.class);
        Set<Edge> seen = new HashSet<>();
        for (RawEdge raw : rawEdges) {
            if (!"HUMAN_VALIDATED".equals(raw.validationStatus())) {
                continue;   // held (SUGGESTED) / REVIEW_REQUIRED / anything else: excluded
            }
            SemanticRelation relation = parseRelation(raw.relation());
            if (relation == null) {
                throw new IllegalArgumentException(
                        "concept graph: unknown relation '" + raw.relation() + "' on edge "
                                + raw.source() + " -> " + raw.target());
            }
            requireKnown(raw.source(), knownNodeCodes);
            requireKnown(raw.target(), knownNodeCodes);
            Edge edge = new Edge(raw.source(), relation, raw.target());
            if (!seen.add(edge)) {
                throw new IllegalArgumentException(
                        "concept graph: duplicate validated edge " + raw.source() + " -["
                                + raw.relation() + "]-> " + raw.target());
            }
            collected.computeIfAbsent(relation, r -> new ArrayList<>()).add(edge);
        }
        Map<SemanticRelation, List<Edge>> frozen = new EnumMap<>(SemanticRelation.class);
        for (Map.Entry<SemanticRelation, List<Edge>> e : collected.entrySet()) {
            List<Edge> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparing(Edge::source)
                    .thenComparing(Edge::target)
                    .thenComparing(Edge::relation));
            frozen.put(e.getKey(), List.copyOf(sorted));
        }
        return new ConceptDependencyGraph(frozen);
    }

    /** Validated edges of one relation kind, deterministically ordered (source, target). */
    public List<Edge> edges(SemanticRelation relation) {
        return byRelation.getOrDefault(relation, List.of());
    }

    /** Total number of validated semantic edges carried by this layer. */
    public int validatedEdgeCount() {
        return validatedEdgeCount;
    }

    public boolean isEmpty() {
        return validatedEdgeCount == 0;
    }

    /** Which relation kinds this layer currently carries (for logs and tests). */
    public Set<SemanticRelation> carriedRelations() {
        return EnumSet.copyOf(byRelation.isEmpty()
                ? EnumSet.noneOf(SemanticRelation.class)
                : byRelation.keySet());
    }

    private static SemanticRelation parseRelation(String relation) {
        if (relation == null) {
            return null;
        }
        for (SemanticRelation r : SemanticRelation.values()) {
            if (r.name().equals(relation)) {
                return r;
            }
        }
        return null;
    }

    private static void requireKnown(String code, Set<String> knownNodeCodes) {
        if (!knownNodeCodes.contains(code)) {
            throw new IllegalArgumentException(
                    "concept graph: edge endpoint '" + code + "' is not a known concept node");
        }
    }
}
