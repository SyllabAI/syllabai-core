package com.syllabai.knowledge;

import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles read models over the KG: nested topic trees, prerequisite chains,
 * misconception lists (Master Spec §6.3).
 *
 * <p>Tree depth is bounded by the syllabus hierarchy (subject→unit→topic→subtopic,
 * 4–5 levels); since the batched builder below, a whole tree costs three bulk
 * queries (subtree + PART_OF edges [+ misconception family] + the root row),
 * not one children-query per node.</p>
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeGraphService {

    private final KnowledgeNodeRepository nodes;
    private final KnowledgeEdgeRepository edges;
    private final KnowledgeGraphRepository graph;

    public KnowledgeGraphService(KnowledgeNodeRepository nodes,
                                 KnowledgeEdgeRepository edges,
                                 KnowledgeGraphRepository graph) {
        this.nodes = nodes;
        this.edges = edges;
        this.graph = graph;
    }

    /**
     * Nested PART_OF tree under a root node (typically a SUBJECT node linked from
     * the curriculum module).
     *
     * <p>CLA latency follow-up (audit 2026-10-02): the CLA serving path reads
     * the plain subject tree 2–3× per ask (the resolver spine once per context
     * kind — NOTE_SECTION twice — plus the framing/evidence pass in
     * ClaService), and the public tree endpoint once per read, yet the tree
     * changes only through curriculum ingest and teacher validation waves. A
     * 30-second per-root TTL snapshot (the {@link #structureNodes()} pattern)
     * collapses those repeats: within one ask the resolver and ClaService now
     * share a hit, and repeat asks on the same subject ride the same
     * generation — the per-ask tree cost drops from 6–9 queries to ~0 warm.
     *
     * <p>What does NOT ride the cache: every load-bearing §1.2 validation gate
     * re-runs on FRESH node reads after resolution ({@code nodes.findById} in
     * the resolver spine), so an anchor is never admitted on snapshot age
     * alone. What does: ≤30s-late candidate scanning — the note-anchor and
     * spec-structure candidate filters read validationStatus off the tree. A
     * just-un-validated node can linger ≤30s as a candidate, and the spine's
     * fresh gate refuses it there (fail-closed direction preserved); a
     * just-validated node stays invisible ≤30s (availability blip only).
     *
     * <p>Cache shape: a bounded concurrent map keyed by root id, one immutable
     * generation per entry, wholesale clear past the bound (subject roots are
     * few and stable — the bound is a memory guard, not an eviction policy).
     * Never tunable, never shared across instances; a failed build (unknown
     * root → NotFound) is never cached. Concurrent missers may both build —
     * the read model is idempotent and the loser's copy is simply discarded.</p>
     */
    public NodeView tree(UUID rootId) {
        TreeSnapshot snapshot = treeCache.get(rootId);
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.nanos() < TREE_CACHE_TTL_NANOS) {
            return snapshot.root();
        }
        if (treeCache.size() >= TREE_CACHE_MAX_ENTRIES) {
            treeCache.clear();   // wholesale: a rebuild is one subtree read
        }
        NodeView fresh = buildTree(node(rootId), false);
        treeCache.put(rootId, new TreeSnapshot(fresh, now));
        return fresh;
    }

    /** CLA follow-up: in-process TTL for {@link #tree(UUID)} — 30s, wholesale expiry. */
    private static final long TREE_CACHE_TTL_NANOS = 30_000_000_000L;

    /** Memory guard, not an eviction policy: subject roots are few and stable. */
    private static final int TREE_CACHE_MAX_ENTRIES = 16;

    /** One immutable cache generation: the assembled tree + when it was read. */
    private record TreeSnapshot(NodeView root, long nanos) {
    }

    private final ConcurrentHashMap<UUID, TreeSnapshot> treeCache =
            new ConcurrentHashMap<>();

    /**
     * Same tree with misconception nodes folded in as children of the nodes they
     * attach to. Not cached — the folded variant is not on the CLA path and the
     * learner/class surfaces re-project it per request anyway.
     *
     * <p>Since V15 the fold covers two attachment shapes: TOPIC/SUBTOPIC
     * nodes (V6 contract: MISCONCEPTION_OF into the topic) and CONCEPT nodes of
     * the T-C11 settled graph, whose misconceptions attach via REMEDIATED_BY /
     * WRONG_ANSWER_PATTERN as well — 13 of the 15 settled misconceptions carry no
     * MISCONCEPTION_OF edge at all. Read-model widening only: the underlying
     * edges are exactly the seeded store edges, nothing is derived or invented.
     */
    public NodeView treeWithMisconceptions(UUID rootId) {
        return buildTree(node(rootId), true);
    }

    /**
     * Transitive prerequisite closure, deepest-first — the remediation path a
     * struggling student should walk backwards (Paper A type-1a prerequisite gaps).
     */
    public List<PrerequisiteView> prerequisiteChain(UUID nodeId) {
        return graph.findPrerequisiteClosure(nodeId).stream()
                .map(PrerequisiteView::from)
                .toList();
    }

    /**
     * Batched prerequisite chains for several origin nodes (M3 tranche 2,
     * audit 2026-10-02): ONE recursive-CTE pass + ONE node fetch for the whole
     * matched-topic set instead of a three-query chain per topic. Keyed by
     * origin id (missing key = no prerequisites); within-origin order matches
     * {@link #prerequisiteChain(UUID)} exactly.
     */
    public Map<UUID, List<PrerequisiteView>> prerequisiteChains(Collection<UUID> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PrerequisiteView>> result = new LinkedHashMap<>();
        graph.findPrerequisiteClosures(nodeIds).forEach((origin, closure) ->
                result.put(origin, closure.stream().map(PrerequisiteView::from).toList()));
        return result;
    }

    public List<NodeView> misconceptions(UUID topicNodeId) {
        return graph.findMisconceptions(topicNodeId).stream()
                .map(NodeView::flat)
                .toList();
    }

    /**
     * Batched misconception attachments for several topic nodes (M3 tranche
     * 2): one edge query for all matched topics instead of one per topic.
     * Keyed by topic id (missing key = none attached); within-topic content
     * matches {@link #misconceptions(UUID)}.
     */
    public Map<UUID, List<NodeView>> misconceptionsForTopics(Collection<UUID> topicNodeIds) {
        if (topicNodeIds == null || topicNodeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<NodeView>> result = new LinkedHashMap<>();
        graph.findMisconceptionsForTopics(topicNodeIds).forEach((topic, mis) ->
                result.put(topic, mis.stream().map(NodeView::flat).toList()));
        return result;
    }

    public KnowledgeNode node(UUID id) {
        return nodes.findById(id)
                .orElseThrow(() -> new NotFoundException("knowledge node", id));
    }

    /**
     * The node ids of a root's whole PART_OF subtree (inclusive) — the bulk
     * scope for read models that need "everything under this subject" (V15
     * teacher concept-graph edges).
     */
    public List<UUID> subtreeIds(UUID rootId) {
        node(rootId);   // same 404 contract as the other read methods
        return nodes.findSubtreeIds(rootId);
    }

    /**
     * The settled T-C11 concept layer's anchor edges within a root's subtree:
     * PART_OF edges sourced at CONCEPT nodes (concept → anchored
     * SpecificationPoint), bulk-loaded in one query and code-ordered.
     *
     * <p>The projection basis for structure-keyed read models: the settled
     * store carries prerequisite relations at concept level (98 concepts + 12
     * required practicals), while class-level surfaces aggregate mastery at
     * curriculum-structure level — a concept is never directly measured. Its
     * VALIDATED anchor edges carry the official placement, so a read model may
     * project a concept-level prerequisite onto the SpecificationPoint(s) that
     * teach it. Read-model widening only: the underlying edges are exactly the
     * seeded store edges (V15 anchors), nothing is derived beyond placement.</p>
     */
    public List<KnowledgeEdge> conceptAnchorsWithin(UUID rootId) {
        node(rootId);   // same 404 contract as the other read methods
        return edges.findConceptAnchorEdgesWithin(nodes.findSubtreeIds(rootId)).stream()
                .sorted(java.util.Comparator
                        .comparing((KnowledgeEdge e) -> e.source().code())
                        .thenComparing(e -> e.target().code()))
                .toList();
    }

    /**
     * Every UNIT/TOPIC/SUBTOPIC node, code-ordered — the deterministic intent
     * surface for KA-RAG (T-024): query tokens are matched against these
     * titles; nothing outside the KG may be inferred.
     *
     * <p>M3 (audit 2026-10-02): the structure list is read on EVERY tutor ask
     * (the GraphKnowledgeRetriever intent matcher is this method's only
     * production caller) yet changes only through curriculum ingest and
     * teacher validation waves. A 30-second in-process TTL trims one
     * full-structure query per ask; a ≤30s-late node list is immaterial for
     * title-token matching, and the per-node VALIDATED gate still runs in the
     * retriever AFTER the cache, so serving eligibility never rides on it.
     * The cache is a single immutable snapshot swapped wholesale (one volatile
     * reference — no partial visibility), never tunable, never shared across
     * instances: one constant, fail-safe expiry.</p>
     */
    public List<KnowledgeNode> structureNodes() {
        StructureSnapshot snapshot = structureCache;
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.nanos() < STRUCTURE_CACHE_TTL_NANOS) {
            return snapshot.nodes();
        }
        List<KnowledgeNode> fresh = List.copyOf(nodes.findStructureNodes());
        structureCache = new StructureSnapshot(fresh, now);
        return fresh;
    }

    /** M3 tranche 1: in-process TTL for {@link #structureNodes()} — 30s, wholesale expiry. */
    private static final long STRUCTURE_CACHE_TTL_NANOS = 30_000_000_000L;

    /** One immutable cache generation: the node list + when it was read. */
    private record StructureSnapshot(List<KnowledgeNode> nodes, long nanos) {
    }

    private volatile StructureSnapshot structureCache;

    /**
     * Direct prerequisite relations among the subtree's structure nodes — the
     * drawable edges for the personalized mastery-map read model (F-034).
     * One batched query over the recursive-CTE subtree, so graph visualisation
     * never triggers per-node prerequisite lookups. Misconception nodes cannot
     * be prerequisites and are excluded by construction (they attach via
     * MISCONCEPTION_OF, not PART_OF, so the subtree never contains them).
     */
    public List<PrerequisiteRelation> prerequisiteRelations(UUID rootId) {
        List<UUID> ids = graph.findSubtree(rootId).stream()
                .map(KnowledgeNode::id)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return edges.findPrerequisiteEdgesWithin(ids).stream()
                // edge convention (V6 seed + T-C11 authored graph): source = the
                // DEPENDENT node, target = the PREREQUISITE it requires — the
                // record is (prerequisiteId, dependentNodeId), so target→prerequisite
                .map(e -> new PrerequisiteRelation(e.targetId(), e.sourceId()))
                .toList();
    }

    /** A drawable prerequisite edge: {@code prerequisiteId} → {@code dependentNodeId}. */
    public record PrerequisiteRelation(UUID prerequisiteId, UUID dependentNodeId) {
    }

    // ── internals ──────────────────────────────────────────────────

    // ── batched tree assembly (one query per tree, not per node) ──────────────

    /**
     * Batched equivalent of the recursive {@link #toView} walk: the whole
     * PART_OF subtree, its PART_OF child edges and its misconception-family
     * attachments are loaded in THREE bulk queries and the NodeView tree is
     * assembled in memory. Child ordering (source code, PART_OF children first,
     * misconception children appended) matches the per-node semantics exactly:
     * TOPIC/SUBTOPIC nodes take MISCONCEPTION_OF attachments only, CONCEPT
     * nodes the full misconception family, deduped by source per target.
     */
    private NodeView buildTree(KnowledgeNode root, boolean withMisconceptions) {
        List<KnowledgeNode> subtree = graph.findSubtree(root.id());
        Map<UUID, KnowledgeNode> byId = new HashMap<>();
        for (KnowledgeNode n : subtree) {
            byId.put(n.id(), n);
        }
        List<UUID> ids = new ArrayList<>(byId.keySet());

        Map<UUID, List<KnowledgeEdge>> childrenByParent = new HashMap<>();
        for (KnowledgeEdge e : edges.findPartOfEdgesWithin(ids)) {
            childrenByParent.computeIfAbsent(e.targetId(), k -> new ArrayList<>()).add(e);
        }

        Map<UUID, List<KnowledgeEdge>> attachmentsByTarget = new HashMap<>();
        if (withMisconceptions) {
            for (KnowledgeEdge e : edges.findMisconceptionFamilyEdgesWithin(ids)) {
                attachmentsByTarget.computeIfAbsent(e.targetId(), k -> new ArrayList<>()).add(e);
            }
        }

        return assemble(root, byId, childrenByParent, attachmentsByTarget, withMisconceptions);
    }

    private NodeView assemble(KnowledgeNode node,
                              Map<UUID, KnowledgeNode> byId,
                              Map<UUID, List<KnowledgeEdge>> childrenByParent,
                              Map<UUID, List<KnowledgeEdge>> attachmentsByTarget,
                              boolean withMisconceptions) {
        List<NodeView> children = new ArrayList<>();
        List<KnowledgeEdge> kids = new ArrayList<>(
                childrenByParent.getOrDefault(node.id(), List.of()));
        kids.sort(java.util.Comparator.comparing(e -> e.source().code()));
        for (KnowledgeEdge e : kids) {
            KnowledgeNode child = byId.get(e.sourceId());
            if (child != null) {   // dangling edge → skipped, matching the walk contract
                children.add(assemble(child, byId, childrenByParent,
                        attachmentsByTarget, withMisconceptions));
            }
        }

        if (withMisconceptions) {
            boolean topicLike = node.nodeType() == NodeType.TOPIC
                    || node.nodeType() == NodeType.SUBTOPIC;
            if (topicLike || node.nodeType() == NodeType.CONCEPT) {
                java.util.Set<UUID> attached = new java.util.HashSet<>();
                List<KnowledgeEdge> attachments = new ArrayList<>(
                        attachmentsByTarget.getOrDefault(node.id(), List.of()));
                attachments.removeIf(e -> topicLike
                        ? e.relationType() != RelationType.MISCONCEPTION_OF
                        : false);
                attachments.sort(java.util.Comparator.comparing(e -> e.source().code()));
                for (KnowledgeEdge e : attachments) {
                    // dedupe: a concept may be connected to the same misconception
                    // through several family edges — the per-node DISTINCT query did
                    if (attached.add(e.sourceId())) {
                        // misconception sources are NOT subtree members (they attach
                        // into it), so resolve through the join-fetched edge source
                        children.add(NodeView.flat(e.source()));
                    }
                }
            }
        }

        return new NodeView(node.id(), node.code(), node.nodeType().name(), node.title(),
                node.description(), node.validationStatus().name(), node.provenance(),
                node.applicability(),
                List.copyOf(children));
    }
}
