package com.syllabai.teacher;

import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ConceptGraphSnapshotLoader.ConceptGraphSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher-side 4CH1 concept-graph seed (V15, session 56): materializes the
 * pinned curriculum substrate + the settled T-C11 graph into the EXISTING
 * Postgres knowledge graph — the same {@code knowledge_nodes}/
 * {@code knowledge_edges} tables the V6 seed, the curriculum review workflow
 * and the learner loop already use. No second store, no Neo4j, no LLM.
 *
 * <p>Activation is deterministic and idempotent (the operator's activation
 * rules): the input is the SHA-256-pinned snapshot, node/edge identity is the
 * store's own codes, and every row is resolved by code — same code + same
 * provenance ⇒ reuse, same code + different provenance ⇒ loud conflict (the
 * {@code CurriculumIngestionService} contract). Re-running the seed with the
 * same snapshot is a structural no-op: the counts report reused rows.</p>
 *
 * <p>Statuses preserve the store's epistemic state (§8A.4 — validation never
 * erases provenance):
 * <ul>
 *   <li>curriculum structure (root, 4 sections, 28 subsections, 182 spec
 *       points, 12 practicals) + structure PART_OF edges land VALIDATED —
 *       official specification content, rule-derived extract, operator git-PR
 *       gate (the V6 seed marked spec structure VALIDATED for the same
 *       reason);</li>
 *   <li>concept/misconception nodes land SUGGESTED — the settled store's own
 *       node status (operator-confirmed extraction identities);</li>
 *   <li>the 117 concept→SP anchor PART_OF edges land SUGGESTED — the store's
 *       own anchor status (extraction-derived);</li>
 *   <li>the 153 HUMAN_VALIDATED semantic edges land VALIDATED with T-C11
 *       provenance naming the extraction pass and the operator validation.</li>
 * </ul>
 * The 3 pilot HOLD and 2 REVIEW_REQUIRED edges never reach the KG — the
 * loader fail-closes on their count. The curriculum version lands ACTIVE (the
 * V6 SQL-seed precedent: an operator-reviewed official-spec seed is not an
 * unreviewed parser draft; node-level review stays available through the §7
 * workflow).</p>
 *
 * <p>Because node codes are the store's codes verbatim, the seeded concepts
 * join the learner Next-Best-Action engine's code registry automatically
 * (session 55, nba-rules/v1.1) — this seed is what activates the graph-aware
 * recommendation stages against real curriculum rows.</p>
 */
@Service
public class ConceptGraphSeedService {

    private static final Logger log = LoggerFactory.getLogger(ConceptGraphSeedService.class);

    static final String BOARD = "Edexcel";
    static final String QUALIFICATION = "International GCSE (9-1)";
    static final String CURRICULUM_CODE = "4CH1-2017";
    static final String CURRICULUM_TITLE =
            "Pearson Edexcel International GCSE (9-1) Chemistry (4CH1, Issue 3)";
    static final String SUBJECT_CODE = "4CH1";
    static final String SUBJECT_NAME = "Chemistry (4CH1)";
    static final String ROOT_NODE_CODE = "4CH1";
    static final String SEED_IDENTITY = "concept-graph-seed-v1";

    static final String STRUCTURE_PROVENANCE =
            "spec:4CH1-2017|tier:RULE_DERIVED|gate:operator-git-PR|extract:c09_spec_graph_extract.py";
    static final String CONCEPT_PROVENANCE =
            "t-c11:settled|tier:AI_SUGGESTED|identity:operator-reviewed";
    static final String ANCHOR_PROVENANCE =
            "t-c11:settled|anchor:AI_SUGGESTED";

    private final ConceptGraphSnapshotLoader snapshotLoader;
    private final CurriculumVersionRepository curriculumVersions;
    private final SubjectRepository subjects;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final KnowledgeEdgeRepository knowledgeEdges;

    public ConceptGraphSeedService(ConceptGraphSnapshotLoader snapshotLoader,
                                   CurriculumVersionRepository curriculumVersions,
                                   SubjectRepository subjects,
                                   KnowledgeNodeRepository knowledgeNodes,
                                   KnowledgeEdgeRepository knowledgeEdges) {
        this.snapshotLoader = snapshotLoader;
        this.curriculumVersions = curriculumVersions;
        this.subjects = subjects;
        this.knowledgeNodes = knowledgeNodes;
        this.knowledgeEdges = knowledgeEdges;
    }

    /**
     * Materializes (or verifies, on re-run) the 4CH1 curriculum + settled
     * concept graph. Single transaction: the full seed lands or nothing does.
     */
    @Transactional
    public SeedSummary activate(UUID activatedBy) {
        ConceptGraphSnapshot snapshot = snapshotLoader.load();

        CurriculumVersion version = resolveVersion();
        Subject subject = resolveSubject(version);
        // the counters must see the root too: the summary's created/reused
        // counts report EVERY row the seed owns, root included (pilot-readiness
        // session-56 finding — the IT and the UI both count 1 + structure +
        // concept nodes, the counter was one short)
        Counters counters = new Counters();
        KnowledgeNode root = resolveRoot(subject, activatedBy, counters);

        Map<String, KnowledgeNode> byCode = new HashMap<>();
        byCode.put(root.code(), root);

        // curriculum structure: sections → subsections → spec points
        Map<String, KnowledgeNode> sectionNodes = new HashMap<>();
        for (ConceptGraphSnapshot.Section section : snapshot.sections()) {
            KnowledgeNode node = resolveNode(byCode, section.code(), NodeType.UNIT,
                    bound(section.title(), 200),
                    structureDescription("section", section.title()),
                    KnowledgeNode.ValidationStatus.VALIDATED, STRUCTURE_PROVENANCE, activatedBy,
                    counters);
            sectionNodes.put(section.code(), node);
            attach(node, root, "spec structure (section)", STRUCTURE_PROVENANCE,
                    KnowledgeNode.ValidationStatus.VALIDATED, activatedBy, counters);
        }
        Map<String, KnowledgeNode> subsectionNodes = new HashMap<>();
        for (ConceptGraphSnapshot.Subsection subsection : snapshot.subsections()) {
            KnowledgeNode node = resolveNode(byCode, subsection.code(), NodeType.TOPIC,
                    bound(subsection.title(), 200),
                    structureDescription("subsection", subsection.title()),
                    KnowledgeNode.ValidationStatus.VALIDATED, STRUCTURE_PROVENANCE, activatedBy,
                    counters);
            subsectionNodes.put(subsection.code(), node);
            attach(node, sectionNodes.get(subsection.sectionCode()), "spec structure (subsection)",
                    STRUCTURE_PROVENANCE, KnowledgeNode.ValidationStatus.VALIDATED, activatedBy,
                    counters);
        }
        Map<String, KnowledgeNode> specPointNodes = new HashMap<>();
        for (ConceptGraphSnapshot.SpecPoint sp : snapshot.specPoints()) {
            KnowledgeNode node = resolveNode(byCode, sp.code(), NodeType.SUBTOPIC,
                    bound(sp.wording(), 200), spDescription(sp),
                    KnowledgeNode.ValidationStatus.VALIDATED, STRUCTURE_PROVENANCE, activatedBy,
                    counters);
            specPointNodes.put(sp.code(), node);
            attach(node, subsectionNodes.get(sp.subsectionCode()), "spec structure (spec point)",
                    STRUCTURE_PROVENANCE, KnowledgeNode.ValidationStatus.VALIDATED, activatedBy,
                    counters);
        }

        // required practicals: official spec content anchored on their SP
        for (ConceptGraphSnapshot.Practical practical : snapshot.practicals()) {
            KnowledgeNode node = resolveNode(byCode, practical.code(), NodeType.SUBTOPIC,
                    bound("Required practical: " + practical.summary(), 200),
                    structureDescription("required practical", practical.summary()),
                    KnowledgeNode.ValidationStatus.VALIDATED, STRUCTURE_PROVENANCE, activatedBy,
                    counters);
            attach(node, specPointNodes.get(practical.specPointCode()), "required practical anchor",
                    STRUCTURE_PROVENANCE, KnowledgeNode.ValidationStatus.VALIDATED, activatedBy,
                    counters);
        }

        // the settled T-C11 layer: concept + misconception nodes (SUGGESTED —
        // the store's own node status), then their anchor + semantic edges
        for (ConceptGraphSnapshot.ConceptNodeRecord record : snapshot.conceptNodes()) {
            NodeType type = "MISCONCEPTION".equals(record.family())
                    ? NodeType.MISCONCEPTION : NodeType.CONCEPT;
            String description = record.aliases().isEmpty()
                    ? "T-C11 settled " + record.family().toLowerCase() + " (no recorded aliases)"
                    : "T-C11 settled " + record.family().toLowerCase() + " — aliases: "
                            + String.join("; ", record.aliases());
            resolveNode(byCode, record.code(), type, bound(record.title(), 200),
                    bound(description, 1000), KnowledgeNode.ValidationStatus.SUGGESTED,
                    CONCEPT_PROVENANCE, activatedBy, counters);
        }
        for (ConceptGraphSnapshot.AnchorEdge anchor : snapshot.anchorEdges()) {
            KnowledgeNode concept = byCode.get(anchor.conceptCode());
            KnowledgeNode specPoint = specPointNodes.get(anchor.specPointCode());
            if (concept == null || specPoint == null) {
                throw new ConflictException("anchor edge " + anchor.conceptCode() + " -> "
                        + anchor.specPointCode() + " does not resolve — store drift");
            }
            attach(concept, specPoint, "concept anchor (role " + anchor.role() + ")",
                    ANCHOR_PROVENANCE, KnowledgeNode.ValidationStatus.SUGGESTED, activatedBy,
                    counters);
        }
        for (ConceptGraphSnapshot.ValidatedEdge edge : snapshot.validatedSemanticEdges()) {
            KnowledgeNode source = byCode.get(edge.source());
            KnowledgeNode target = byCode.get(edge.target());
            if (source == null || target == null) {
                throw new ConflictException("semantic edge " + edge.source() + " -> "
                        + edge.target() + " does not resolve — store drift");
            }
            resolveSemanticEdge(source, target, relationOf(edge.relation()), edge.confidence(),
                    edge.rationale(), edge.provenance(), activatedBy, counters);
        }

        if (version.status() != CurriculumVersion.Status.ACTIVE) {
            version.activate();
        }

        log.info("4CH1 concept graph seed: {} nodes ({} created), {} edges ({} created) — "
                        + "snapshot: {} sections / {} subsections / {} SPs / {} practicals / "
                        + "{} concept nodes / {} validated semantic edges",
                byCode.size(), counters.nodesCreated,
                counters.edgeCreated + counters.edgesReused, counters.edgeCreated,
                snapshot.sections().size(), snapshot.subsections().size(),
                snapshot.specPoints().size(), snapshot.practicals().size(),
                snapshot.conceptNodes().size(), snapshot.validatedSemanticEdges().size());
        return new SeedSummary(version.id(), subject.id(), root.id(),
                snapshot.sections().size(), snapshot.subsections().size(),
                snapshot.specPoints().size(), snapshot.practicals().size(),
                snapshot.conceptNodes().size(), snapshot.validatedSemanticEdges().size(),
                counters.nodesCreated, counters.nodesReused, counters.edgeCreated,
                counters.edgesReused, counters.nodesCreated == 0 && counters.edgeCreated == 0);
    }

    // ── idempotent resolution (the CurriculumIngestionService contract) ──

    private CurriculumVersion resolveVersion() {
        return curriculumVersions
                .findByBoardAndQualificationAndCode(BOARD, QUALIFICATION, CURRICULUM_CODE)
                .orElseGet(() -> curriculumVersions.save(new CurriculumVersion(
                        BOARD, QUALIFICATION, CURRICULUM_CODE, CURRICULUM_TITLE,
                        CurriculumVersion.Status.DRAFT)));
    }

    private Subject resolveSubject(CurriculumVersion version) {
        return subjects.findByCurriculumVersionIdAndCode(version.id(), SUBJECT_CODE)
                .orElseGet(() -> subjects.save(new Subject(version, SUBJECT_CODE, SUBJECT_NAME)));
    }

    private KnowledgeNode resolveRoot(Subject subject, UUID activatedBy, Counters counters) {
        KnowledgeNode root = knowledgeNodes.findByCode(ROOT_NODE_CODE).orElse(null);
        if (root == null && subject.knowledgeNodeId() != null) {
            root = knowledgeNodes.findById(subject.knowledgeNodeId()).orElse(null);
            if (root != null && !ROOT_NODE_CODE.equals(root.code())) {
                throw new ConflictException("subject " + SUBJECT_CODE + " is linked to KG node "
                        + root.code() + ", expected the seed root " + ROOT_NODE_CODE);
            }
        }
        if (root != null) {
            requireSeedNode(root, ROOT_NODE_CODE, STRUCTURE_PROVENANCE);
            counters.nodesReused++;
        } else {
            root = knowledgeNodes.save(new KnowledgeNode(
                    ROOT_NODE_CODE, NodeType.SUBJECT, SUBJECT_NAME,
                    CURRICULUM_TITLE, KnowledgeNode.ValidationStatus.VALIDATED,
                    STRUCTURE_PROVENANCE, author(activatedBy)));
            counters.nodesCreated++;
        }
        if (!root.id().equals(subject.knowledgeNodeId())) {
            subject.linkKnowledgeNode(root.id());
        }
        return root;
    }

    /**
     * Idempotent node resolution by the store code: same code + same seed
     * provenance ⇒ reuse (canonical identity); same code + anything else ⇒
     * loud conflict — never silently re-seed or duplicate a canonical concept.
     */
    private KnowledgeNode resolveNode(Map<String, KnowledgeNode> byCode, String code,
                                      NodeType type, String title, String description,
                                      KnowledgeNode.ValidationStatus status, String provenance,
                                      UUID activatedBy, Counters counters) {
        KnowledgeNode existing = byCode.get(code);
        if (existing == null) {
            existing = knowledgeNodes.findByCode(code).orElse(null);
        }
        if (existing != null) {
            requireSeedNode(existing, code, provenance);
            counters.nodesReused++;
            byCode.put(code, existing);
            return existing;
        }
        KnowledgeNode created = knowledgeNodes.save(new KnowledgeNode(
                code, type, title, description, status, provenance, author(activatedBy)));
        counters.nodesCreated++;
        byCode.put(code, created);
        return created;
    }

    /**
     * Idempotent PART_OF attachment by exact (child, parent) edge identity —
     * 19 settled concepts legitimately anchor under more than one spec point
     * (117 anchors over 98 concepts), so the seed's identity check must be the
     * edge itself, not the single-parent lookup the review workflow uses.
     */
    private void attach(KnowledgeNode child, KnowledgeNode parent, String rationale,
                        String provenance, KnowledgeNode.ValidationStatus status,
                        UUID activatedBy, Counters counters) {
        KnowledgeEdge existing = knowledgeEdges.findBySourceIdAndTargetIdAndRelationType(
                child.id(), parent.id(), RelationType.PART_OF).orElse(null);
        if (existing != null) {
            requireSeedEdge(existing, "PART_OF " + child.code() + " -> " + parent.code(),
                    provenance);
            counters.edgesReused++;
            return;
        }
        knowledgeEdges.save(new KnowledgeEdge(child, parent, RelationType.PART_OF, null,
                rationale, status, provenance, author(activatedBy)));
        counters.edgeCreated++;
    }

    /** Idempotent semantic-edge resolution by the (source, target, relation) identity. */
    private void resolveSemanticEdge(KnowledgeNode source, KnowledgeNode target,
                                     RelationType relation, Double confidence, String rationale,
                                     String provenance, UUID activatedBy, Counters counters) {
        KnowledgeEdge existing = knowledgeEdges
                .findBySourceIdAndTargetIdAndRelationType(source.id(), target.id(), relation)
                .orElse(null);
        if (existing != null) {
            requireSeedEdge(existing, relation.name() + " " + source.code() + " -> "
                    + target.code(), provenance);
            counters.edgesReused++;
            return;
        }
        knowledgeEdges.save(new KnowledgeEdge(source, target, relation, confidence,
                rationale, KnowledgeNode.ValidationStatus.VALIDATED, provenance,
                author(activatedBy)));
        counters.edgeCreated++;
    }

    private static void requireSeedNode(KnowledgeNode node, String code, String provenance) {
        if (!node.provenance().equals(provenance)) {
            throw new ConflictException("knowledge node " + code + " already exists with "
                    + "different provenance ('" + node.provenance() + "' vs seed '"
                    + provenance + "') — resolve manually, never re-seed over it");
        }
    }

    private static void requireSeedEdge(KnowledgeEdge edge, String what, String provenance) {
        if (!edge.provenance().equals(provenance)) {
            throw new ConflictException("knowledge edge " + what + " already exists with "
                    + "different provenance ('" + edge.provenance() + "' vs seed '"
                    + provenance + "') — resolve manually, never re-seed over it");
        }
    }

    private static RelationType relationOf(String storeRelation) {
        return switch (storeRelation) {
            case "REQUIRES_PREREQUISITE" -> RelationType.REQUIRES_PREREQUISITE;
            case "REMEDIATED_BY" -> RelationType.REMEDIATED_BY;
            case "WRONG_ANSWER_PATTERN" -> RelationType.WRONG_ANSWER_PATTERN;
            case "COMMONLY_CONFUSED_WITH" -> RelationType.COMMONLY_CONFUSED_WITH;
            case "MISCONCEPTION_OF" -> RelationType.MISCONCEPTION_OF;
            case "EXPLAINED_BY" -> RelationType.EXPLAINED_BY;
            case "RELATED_TO" -> RelationType.RELATED_TO;
            default -> throw new ConflictException("settled store relation '" + storeRelation
                    + "' has no runtime RelationType mapping — refusing to guess");
        };
    }

    private static String spDescription(ConceptGraphSnapshot.SpecPoint sp) {
        StringBuilder sb = new StringBuilder("Official spec point ").append(sp.officialCode())
                .append(" — ").append(sp.wording());
        if (sp.cPoint()) {
            sb.append(" [C point: Chemistry-only content, not in Science (Double Award)]");
        }
        if (sp.practical()) {
            sb.append(" [has required practical]");
        }
        return bound(sb.toString(), 1000);
    }

    private static String structureDescription(String kind, String title) {
        return bound("4CH1 official specification structure (" + kind + "): " + title, 1000);
    }

    private static String author(UUID activatedBy) {
        return activatedBy == null ? SEED_IDENTITY : bound(activatedBy.toString(), 100);
    }

    private static String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final class Counters {
        int nodesCreated;
        int nodesReused;
        int edgeCreated;
        int edgesReused;
    }

    /**
     * @param curriculumVersionId   the 4CH1-2017 curriculum version
     * @param subjectId             the 4CH1 subject row
     * @param rootNodeId            the KG subject root (the NBA rootId join point)
     * @param sections              snapshot section count (4)
     * @param subsections           snapshot subsection count (28)
     * @param specPoints            snapshot spec-point count (182)
     * @param practicals            snapshot practical count (12)
     * @param conceptNodes          snapshot concept/misconception node count (113)
     * @param validatedSemanticEdges snapshot validated semantic edge count (153)
     * @param nodesCreated          KG nodes created this run (0 on idempotent re-run)
     * @param nodesReused           KG nodes reused (canonical identity preserved)
     * @param edgesCreated          KG edges created this run (0 on idempotent re-run)
     * @param edgesReused           KG edges reused
     * @param alreadyActive         true when the whole seed was already present
     */
    public record SeedSummary(UUID curriculumVersionId, UUID subjectId, UUID rootNodeId,
                              int sections, int subsections, int specPoints, int practicals,
                              int conceptNodes, int validatedSemanticEdges,
                              int nodesCreated, int nodesReused,
                              int edgesCreated, int edgesReused, boolean alreadyActive) {
    }
}
