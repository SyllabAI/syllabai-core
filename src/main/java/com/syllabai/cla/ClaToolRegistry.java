package com.syllabai.cla;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.SkillState;
import com.syllabai.shared.BadRequestException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Server-owned read-only tool registry (CLA contract §4).
 *
 * <p>Policy properties, enforced by construction:</p>
 * <ul>
 *   <li><b>Server-owned:</b> the composition of tools for a request is fixed
 *       and deterministic — the provider never selects tools (LIM §3.10);
 *       there is no agent loop and the per-request tool budget IS the fixed
 *       composition.</li>
 *   <li><b>Read-only:</b> every tool only calls read paths of existing
 *       services (knowledge graph reads, learner's own state reads). No tool
 *       can write the canonical KG, mastery, misconception state, or
 *       validation state — there is no write-capable dependency in this
 *       class.</li>
 *   <li><b>Authorization-aware:</b> {@code GET_LEARNER_STATE} reads the
 *       requesting learner's OWN state only, scoped to the resolved context
 *       subtree; there is no cross-learner read path.</li>
 *   <li><b>Auditable:</b> every invocation returns a {@link ToolTrace}
 *       (tool, arguments reference, result size, latency) that the pipeline
 *       logs into research telemetry (§4.4).</li>
 * </ul>
 *
 * <p>Step 1 tools (exactly what the KG_TOPIC slice needs):</p>
 * <ol>
 *   <li>{@code GET_SPECIFICATION_CONTEXT} — the anchored topic with its spec
 *       ancestor chain (unit → topic), from the resolved subject tree;</li>
 *   <li>{@code GET_RELATED_CONCEPTS} — the topic's transitive prerequisite
 *       chain and its attached misconception nodes (pedagogical context);</li>
 *   <li>{@code GET_LEARNER_STATE} — the learner's own measured skill and
 *       misconception states, scoped to the anchored topic and its
 *       prerequisites (honest nulls when nothing is measured).</li>
 * </ol>
 */
@Component
public class ClaToolRegistry {

    public enum Tool {
        GET_SPECIFICATION_CONTEXT,
        GET_RELATED_CONCEPTS,
        GET_LEARNER_STATE
    }

    /** audit record of one tool invocation (contract §4.4) */
    public record ToolTrace(String tool, String args, int resultSize, long latencyMs) {
    }

    /** bounded read result of a tool execution */
    public record ToolResult(Tool tool, int resultSize) {
    }

    /** spec-chain entry of the anchored context (GET_SPECIFICATION_CONTEXT) */
    public record SpecAnchor(UUID nodeId, String code, String type, String title, int depth) {
    }

    /** the learner's OWN measured state, scoped to the context (GET_LEARNER_STATE) */
    public record OwnLearnerState(List<SkillState> skills, List<MisconceptionState> misconceptions) {

        public boolean isEmpty() {
            return skills.isEmpty() && misconceptions.isEmpty();
        }
    }

    /** misconception nodes attached to the anchored topic (GET_RELATED_CONCEPTS) */
    public record RelatedMisconception(UUID nodeId, String title) {
    }

    private static final int MAX_SPEC_CHAIN = 8;
    private static final int MAX_PREREQUISITES = 12;
    private static final int MAX_MISCONCEPTIONS = 6;

    private final KnowledgeGraphService graph;
    private final LearnerModelService learnerModel;

    public ClaToolRegistry(KnowledgeGraphService graph, LearnerModelService learnerModel) {
        this.graph = graph;
        this.learnerModel = learnerModel;
    }

    /**
     * Enablement by (context kind, mode) pair (contract §4.1). Steps 1–2 +
     * the part-level, smart-lesson and note kinds: all three tools are
     * enabled for KG_TOPIC, SPECIFICATION_POINT, PAST_PAPER_QUESTION,
     * QUESTION_PART, SMART_LESSON and NOTE_SECTION with every served mode;
     * any other kind is rejected — the registry, not the caller, decides.
     */
    public List<Tool> enabledFor(ResourceContext.Kind kind, ResponseMode mode) {
        if (kind != ResourceContext.Kind.KG_TOPIC
                && kind != ResourceContext.Kind.SPECIFICATION_POINT
                && kind != ResourceContext.Kind.PAST_PAPER_QUESTION
                && kind != ResourceContext.Kind.QUESTION_PART
                && kind != ResourceContext.Kind.SMART_LESSON
                && kind != ResourceContext.Kind.NOTE_SECTION) {
            throw new BadRequestException("context kind not supported by this runtime step: " + kind);
        }
        return List.of(Tool.values());
    }

    /** GET_SPECIFICATION_CONTEXT: the anchored topic + its ancestor chain. */
    public ToolResultWith<List<SpecAnchor>> specificationContext(ResourceContext context,
                                                                 NodeView subjectTree) {
        List<SpecAnchor> chain = new ArrayList<>();
        collectChain(subjectTree, context.topicNodeId(), 0, chain);
        return new ToolResultWith<>(Tool.GET_SPECIFICATION_CONTEXT,
                "root=" + context.rootId() + ",topic=" + context.topicNodeId(),
                List.copyOf(chain));
    }

    /** GET_RELATED_CONCEPTS: prerequisite chain + attached misconceptions. */
    public ToolResultWith<RelatedConcepts> relatedConcepts(ResourceContext context) {
        List<PrerequisiteView> prerequisites = graph.prerequisiteChain(context.topicNodeId());
        List<NodeView> misconceptionNodes = graph.misconceptions(context.topicNodeId());
        List<RelatedMisconception> misconceptions = misconceptionNodes.stream()
                .limit(MAX_MISCONCEPTIONS)
                .map(m -> new RelatedMisconception(m.id(), m.title()))
                .toList();
        RelatedConcepts result = new RelatedConcepts(
                prerequisites.stream().limit(MAX_PREREQUISITES).toList(), misconceptions);
        return new ToolResultWith<>(Tool.GET_RELATED_CONCEPTS,
                "topic=" + context.reference(), result);
    }

    /**
     * GET_LEARNER_STATE: the requesting learner's OWN measured state, scoped
     * to the anchored topic and its prerequisite nodes. Nothing is fabricated:
     * an unmeasured topic yields an empty (honest) result.
     */
    public ToolResultWith<OwnLearnerState> learnerState(UUID learnerId,
                                                        Collection<UUID> scopeNodeIds) {
        List<SkillState> skills = learnerModel.skillStates(learnerId).stream()
                .filter(s -> scopeNodeIds.contains(s.nodeId()))
                .toList();
        List<MisconceptionState> misconceptions = learnerModel.misconceptionStates(learnerId).stream()
                .filter(m -> scopeNodeIds.contains(m.misconceptionNodeId()))
                .toList();
        OwnLearnerState state = new OwnLearnerState(List.copyOf(skills), List.copyOf(misconceptions));
        return new ToolResultWith<>(Tool.GET_LEARNER_STATE,
                "learner=SELF,scope=" + scopeNodeIds.size() + "-nodes", state);
    }

    /** result wrapper carrying the audit trace next to the value */
    public record ToolResultWith<T>(Tool tool, String args, T value) {

        public int resultSize() {
            return switch (value) {
                case List<?> l -> l.size();
                case RelatedConcepts r -> r.prerequisites().size() + r.misconceptions().size();
                case OwnLearnerState s -> s.skills().size() + s.misconceptions().size();
                default -> 1;
            };
        }
    }

    public record RelatedConcepts(List<PrerequisiteView> prerequisites,
                                  List<RelatedMisconception> misconceptions) {
    }

    /** walk the tree from the root down to the anchored topic, collecting the chain */
    private boolean collectChain(NodeView node, UUID targetId, int depth, List<SpecAnchor> chain) {
        if (node == null || depth > MAX_SPEC_CHAIN || chain.size() >= MAX_SPEC_CHAIN) {
            return false;
        }
        chain.add(new SpecAnchor(node.id(), node.code(), node.type(), node.title(), depth));
        if (node.id().equals(targetId)) {
            return true;
        }
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                if (collectChain(child, targetId, depth + 1, chain)) {
                    return true;
                }
            }
        }
        chain.remove(chain.size() - 1); // not on the path to the target — backtrack
        return false;
    }

    /** set of node ids the learner-state read may see for this context */
    public Set<UUID> learnerStateScope(UUID topicNodeId, List<PrerequisiteView> prerequisites) {
        java.util.HashSet<UUID> scope = new java.util.HashSet<>();
        scope.add(topicNodeId);
        for (PrerequisiteView p : prerequisites) {
            scope.add(p.id());
        }
        return java.util.Collections.unmodifiableSet(scope);
    }
}
