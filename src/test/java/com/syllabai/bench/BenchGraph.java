package com.syllabai.bench;

import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeGraphRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-C13 harness (spec §6): wires the REAL production
 * {@link com.syllabai.tutor.GraphKnowledgeRetriever} over the frozen snapshot
 * through an in-memory {@link KnowledgeGraphRepository} stub — zero divergence
 * from production matching semantics (tokenizer, stop list, specificity floor,
 * tie-breaks are the production code, not a port).
 *
 * <p>Semantics mirrored from the port contracts (javadoc of
 * {@code KnowledgeGraphRepository} + the V6/V15 edge conventions recorded in
 * {@code KnowledgeGraphService}):</p>
 * <ul>
 *   <li>{@code findPrerequisiteClosure}: transitive REQUIRES_PREREQUISITE
 *       closure, edge convention source = dependent → target = prerequisite,
 *       deepest first (remediation walk order).</li>
 *   <li>{@code findMisconceptions}: misconception nodes attached to the topic
 *       through any misconception-family edge targeting it
 *       (MISCONCEPTION_OF / REMEDIATED_BY / WRONG_ANSWER_PATTERN), distinct,
 *       code-ordered. Interpretation note recorded in the run manifest: the
 *       production Postgres implementation may restrict topic-like targets to
 *       MISCONCEPTION_OF; this affects only the recorded misconception signals,
 *       never the scored topic ranking.</li>
 * </ul>
 *
 * <p>The two Spring Data repositories are dynamic-proxy stubs: only
 * {@code findStructureNodes()} is routed (the retriever's sole node source);
 * every other call fails loudly — fail-closed, never a silent empty answer.</p>
 */
public final class BenchGraph {

    private final BenchSnapshot snapshot;
    private final KnowledgeGraphService service;
    private final Map<java.util.UUID, KnowledgeNode> nodeById = new HashMap<>();

    public BenchGraph(BenchSnapshot snapshot) {
        this.snapshot = snapshot;
        for (KnowledgeNode n : snapshot.structureNodes()) {
            nodeById.put(n.id(), n);
        }
        KnowledgeGraphRepository graphRepo = new SnapshotGraphRepository();
        KnowledgeNodeRepository nodeRepo = nodeRepoStub(snapshot.structureNodes());
        KnowledgeEdgeRepository edgeRepo = edgeRepoStub();
        this.service = new KnowledgeGraphService(nodeRepo, edgeRepo, graphRepo);
    }

    /** The production KG-side retriever, unmodified, over the frozen snapshot. */
    public com.syllabai.tutor.GraphKnowledgeRetriever retriever() {
        return new com.syllabai.tutor.GraphKnowledgeRetriever(service);
    }

    private final class SnapshotGraphRepository implements KnowledgeGraphRepository {

        @Override
        public List<KnowledgeNode> findPrerequisites(java.util.UUID nodeId) {
            String code = codeOf(nodeId);
            List<KnowledgeNode> out = new ArrayList<>();
            for (BenchSnapshot.Edge e : snapshot.edgesFrom(code)) {
                if ("REQUIRES_PREREQUISITE".equals(e.relation())) {
                    KnowledgeNode target = snapshot.node(e.target());
                    if (target != null) {
                        out.add(target);
                    }
                }
            }
            out.sort(Comparator.comparing(KnowledgeNode::code));
            return out;
        }

        @Override
        public List<PrerequisiteWithDepth> findPrerequisiteClosure(java.util.UUID nodeId) {
            Map<String, Integer> depthByCode = new HashMap<>();
            Deque<String[]> queue = new ArrayDeque<>();   // {code, depth}
            queue.push(new String[] {codeOf(nodeId), "0"});
            Set<String> seen = new HashSet<>();
            seen.add(codeOf(nodeId));
            while (!queue.isEmpty()) {
                String[] current = queue.poll();
                int depth = Integer.parseInt(current[1]);
                for (BenchSnapshot.Edge e : snapshot.edgesFrom(current[0])) {
                    if (!"REQUIRES_PREREQUISITE".equals(e.relation()) || !seen.add(e.target())) {
                        continue;
                    }
                    depthByCode.merge(e.target(), depth + 1, Math::min);
                    queue.push(new String[] {e.target(), String.valueOf(depth + 1)});
                }
            }
            List<PrerequisiteWithDepth> out = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : depthByCode.entrySet()) {
                KnowledgeNode node = snapshot.node(entry.getKey());
                if (node != null) {
                    out.add(new PrerequisiteWithDepth(node, entry.getValue()));
                }
            }
            // deepest first (remediation walk), ties by code for determinism
            out.sort(Comparator.comparingInt(PrerequisiteWithDepth::depth).reversed()
                    .thenComparing(p -> p.node().code()));
            return out;
        }

        @Override
        public List<KnowledgeNode> findSubtree(java.util.UUID nodeId) {
            // PART_OF structure is not carried by the retrieval snapshot and is
            // not used by the intent retriever path; fail-visible via javadoc,
            // empty by design (tree read models are out of harness scope).
            return List.of(snapshot.node(codeOf(nodeId)));
        }

        @Override
        public List<KnowledgeNode> findMisconceptions(java.util.UUID topicNodeId) {
            return familyMisconceptions(codeOf(topicNodeId));
        }

        @Override
        public List<KnowledgeNode> findAssociatedMisconceptions(java.util.UUID nodeId) {
            return familyMisconceptions(codeOf(nodeId));
        }

        private List<KnowledgeNode> familyMisconceptions(String code) {
            Set<String> family = Set.of("MISCONCEPTION_OF", "REMEDIATED_BY", "WRONG_ANSWER_PATTERN");
            LinkedHashSet<KnowledgeNode> out = new LinkedHashSet<>();
            List<KnowledgeNode> hits = new ArrayList<>();
            for (BenchSnapshot.Edge e : snapshot.edgesTo(code)) {
                if (family.contains(e.relation())) {
                    KnowledgeNode source = snapshot.node(e.source());
                    if (source != null && out.add(source)) {
                        hits.add(source);
                    }
                }
            }
            hits.sort(Comparator.comparing(KnowledgeNode::code));
            return hits;
        }
    }

    private String codeOf(java.util.UUID nodeId) {
        KnowledgeNode node = nodeById.get(nodeId);
        if (node == null) {
            throw new IllegalStateException("bench graph: unknown node id " + nodeId + " (fail-closed)");
        }
        return node.code();
    }

    private static KnowledgeNodeRepository nodeRepoStub(List<KnowledgeNode> structureNodes) {
        return (KnowledgeNodeRepository) Proxy.newProxyInstance(
                BenchGraph.class.getClassLoader(),
                new Class<?>[] {KnowledgeNodeRepository.class},
                (proxy, method, args) -> {
                    if ("findStructureNodes".equals(method.getName())) {
                        return structureNodes;
                    }
                    throw new UnsupportedOperationException(
                            "bench node-repo stub: " + method.getName() + " not routed (fail-closed)");
                });
    }

    private static KnowledgeEdgeRepository edgeRepoStub() {
        return (KnowledgeEdgeRepository) Proxy.newProxyInstance(
                BenchGraph.class.getClassLoader(),
                new Class<?>[] {KnowledgeEdgeRepository.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "bench edge-repo stub: " + method.getName()
                                    + " not routed (retriever path never calls it)");
                });
    }
}
