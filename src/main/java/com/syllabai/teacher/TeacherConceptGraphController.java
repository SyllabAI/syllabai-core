package com.syllabai.teacher;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.NotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * Teacher-facing concept-graph endpoints (V15, session 56): activation of the
 * seeded 4CH1 curriculum + settled T-C11 graph, and the graph-derived semantic
 * read model. Route security: /api/v1/teacher/** requires TEACHER/ADMIN.
 *
 * <p>The split of surfaces is deliberate: the curriculum tree (sections,
 * subsections, spec points, concepts, folded misconceptions — with per-node
 * validation status and provenance) is already served by
 * {@code GET /api/v1/knowledge/nodes/{id}/tree?includeMisconceptions=true};
 * this controller adds exactly what that tree cannot express — the semantic
 * edges BETWEEN nodes (prerequisite chains, remediation, wrong-answer
 * patterns, commonly-confused pairs). The UI joins the two, keeping the
 * official curriculum anchor (spec-point tree) visually and semantically
 * distinct from the graph-derived conceptual relationships (this endpoint).</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/concept-graph")
public class TeacherConceptGraphController {

    private final ConceptGraphSeedService seed;
    private final KnowledgeNodeRepository nodes;
    private final KnowledgeEdgeRepository edges;
    private final KnowledgeGraphService graph;

    public TeacherConceptGraphController(ConceptGraphSeedService seed,
                                         KnowledgeNodeRepository nodes,
                                         KnowledgeEdgeRepository edges,
                                         KnowledgeGraphService graph) {
        this.seed = seed;
        this.nodes = nodes;
        this.edges = edges;
        this.graph = graph;
    }

    /**
     * Activates (or re-verifies) the 4CH1 curriculum + settled T-C11 concept
     * graph. Deterministic and idempotent: the input is the SHA-256-pinned
     * snapshot; re-running reuses every canonical node and edge.
     */
    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.OK)
    public ConceptGraphSeedService.SeedSummary activate(@CurrentUserId UUID activatedBy) {
        return seed.activate(activatedBy);
    }

    /**
     * The graph-derived semantic edges within a subject subtree (typically the
     * seeded 4CH1 root). Deterministic order: relation, source code, target
     * code. PART_OF is excluded — curriculum structure is the tree read
     * model's authority; this carries only conceptual relationships, each
     * with its validation status and T-C11 provenance intact.
     *
     * <p>The endpoint scope is the PART_OF subtree PLUS the misconceptions that
     * attach to it through misconception-family edges (they are edge sources,
     * not PART_OF members — the same widening the tree fold applies). Without
     * them the read model dropped every REMEDIATED_BY / WRONG_ANSWER_PATTERN /
     * MISCONCEPTION_OF edge of the settled store (29 of 153; pilot-readiness
     * session-56 fix — the ConceptGraphSeedFlowIT's 153-edge assertion and the
     * web ConceptGraphView's attach-edge filter both expect them).
     */
    @GetMapping("/edges")
    public ConceptGraphEdgesView edges(@RequestParam UUID rootId) {
        KnowledgeNode root = nodes.findById(rootId)
                .orElseThrow(() -> new NotFoundException("knowledge node", rootId));
        List<UUID> subtreeIds = graph.subtreeIds(root.id());
        List<UUID> scope = new java.util.ArrayList<>(subtreeIds);
        edges.findMisconceptionFamilyEdgesWithin(subtreeIds)
                .forEach(e -> scope.add(e.source().id()));
        List<ConceptGraphEdgeView> views = edges.findSemanticEdgesWithin(scope).stream()
                .map(ConceptGraphEdgeView::from)
                .sorted(Comparator.comparing(ConceptGraphEdgeView::relation)
                        .thenComparing(e -> e.source().code())
                        .thenComparing(e -> e.target().code()))
                .toList();
        return new ConceptGraphEdgesView(root.id(), root.code(), "concept-graph-teacher/v1",
                views);
    }

    // ── view records ───────────────────────────────────────────────

    public record ConceptGraphEdgesView(UUID rootId, String rootCode, String policy,
                                        List<ConceptGraphEdgeView> edges) {
    }

    /**
     * One semantic relationship, teacher-facing. The provenance string keeps
     * the T-C11 store's honesty contract: extraction pass, derivation method
     * and the operator validation remain visible without exposing internal
     * machinery beyond what a teacher needs to trust the relationship.
     */
    public record ConceptGraphEdgeView(EdgeNodeView source, EdgeNodeView target,
                                       String relation, String validationStatus,
                                       String provenance, String rationale) {

        static ConceptGraphEdgeView from(KnowledgeEdge edge) {
            return new ConceptGraphEdgeView(
                    EdgeNodeView.from(edge.source()), EdgeNodeView.from(edge.target()),
                    edge.relationType().name(), edge.validationStatus().name(),
                    edge.provenance(), edge.rationale());
        }
    }

    public record EdgeNodeView(UUID nodeId, String code, String title, String nodeType,
                               String validationStatus) {

        static EdgeNodeView from(KnowledgeNode node) {
            return new EdgeNodeView(node.id(), node.code(), node.title(),
                    node.nodeType().name(), node.validationStatus().name());
        }
    }
}
