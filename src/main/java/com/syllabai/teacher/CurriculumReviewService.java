package com.syllabai.teacher;

import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// read methods open a session so PART_OF edge targets resolve; mutating
// methods below declare their own read-write @Transactional (which overrides)

/**
 * Teacher validation workflow for spec-derived curricula (T-010, Master Spec
 * §7). Ingested nodes arrive SUGGESTED and are validated one by one — the
 * reviewer is confirming "yes, this node is really in the spec, correctly
 * titled and correctly placed" against the cited provenance. A curriculum
 * version becomes ACTIVE only when its whole PART_OF tree is VALIDATED:
 * unreviewed seeds must never gate what serves to learners.
 */
@Service
@Transactional(readOnly = true)
public class CurriculumReviewService {

    private static final Logger log = LoggerFactory.getLogger(CurriculumReviewService.class);

    private final CurriculumVersionRepository curriculumVersions;
    private final SubjectRepository subjects;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final KnowledgeEdgeRepository knowledgeEdges;

    public CurriculumReviewService(CurriculumVersionRepository curriculumVersions,
                                   SubjectRepository subjects,
                                   KnowledgeNodeRepository knowledgeNodes,
                                   KnowledgeEdgeRepository knowledgeEdges) {
        this.curriculumVersions = curriculumVersions;
        this.subjects = subjects;
        this.knowledgeNodes = knowledgeNodes;
        this.knowledgeEdges = knowledgeEdges;
    }

    public List<CurriculumOverview> versions() {
        List<CurriculumOverview> overviews = new ArrayList<>();
        for (CurriculumVersion version : curriculumVersions.findAllByOrderByCreatedAtDesc()) {
            int validated = 0;
            int suggested = 0;
            int unvalidated = 0;
            for (Subject subject : subjects
                    .findByCurriculumVersionIdOrderByCode(version.id())) {
                Map<KnowledgeNode.ValidationStatus, Integer> counts =
                        treeCounts(subject.knowledgeNodeId());
                validated += counts.getOrDefault(KnowledgeNode.ValidationStatus.VALIDATED, 0);
                suggested += counts.getOrDefault(KnowledgeNode.ValidationStatus.SUGGESTED, 0);
                unvalidated += counts.getOrDefault(KnowledgeNode.ValidationStatus.UNVALIDATED, 0);
            }
            overviews.add(new CurriculumOverview(version.id(), version.board(),
                    version.qualification(), version.code(), version.title(),
                    version.status().name(), validated, suggested, unvalidated));
        }
        return overviews;
    }

    /** The review queue for one curriculum: nodes by validation status. */
    public List<NodeView> nodes(UUID curriculumVersionId, KnowledgeNode.ValidationStatus status) {
        List<NodeView> views = new ArrayList<>();
        for (Subject subject : subjects
                .findByCurriculumVersionIdOrderByCode(curriculumVersionId)) {
            for (UUID nodeId : knowledgeNodes.findSubtreeIds(subject.knowledgeNodeId())) {
                KnowledgeNode node = knowledgeNodes.findById(nodeId).orElse(null);
                if (node == null || (status != null && node.validationStatus() != status)) {
                    continue;
                }
                views.add(NodeView.from(node, parentIdOf(node)));
            }
        }
        views.sort(Comparator.comparing(NodeView::code));
        return views;
    }

    @Transactional
    public NodeView validateNode(UUID nodeId) {
        KnowledgeNode node = node(nodeId);
        node.validate();
        knowledgeEdges.findBySourceIdAndRelationType(node.id(), RelationType.PART_OF)
                .ifPresent(KnowledgeEdge::validate);
        log.info("curriculum node {} ({}) validated", node.code(), node.validationStatus());
        return NodeView.from(node, parentIdOf(node));
    }

    @Transactional
    public NodeView rejectNode(UUID nodeId) {
        KnowledgeNode node = node(nodeId);
        node.markUnvalidated();
        knowledgeEdges.findBySourceIdAndRelationType(node.id(), RelationType.PART_OF)
                .ifPresent(KnowledgeEdge::markUnvalidated);
        log.info("curriculum node {} ({}) rejected back to UNVALIDATED", node.code());
        return NodeView.from(node, parentIdOf(node));
    }

    /**
     * The version gate: ACTIVE only when every node of every subject tree is
     * VALIDATED (misconception nodes hang off MISCONCEPTION_OF edges and are
     * validated separately — they are curation, not spec structure).
     */
    @Transactional
    public CurriculumOverview validateVersion(UUID curriculumVersionId) {
        CurriculumVersion version = curriculumVersions.findById(curriculumVersionId)
                .orElseThrow(() -> new NotFoundException("curriculum version", curriculumVersionId));

        Map<KnowledgeNode.ValidationStatus, Integer> counts = Map.of();
        for (Subject subject : subjects
                .findByCurriculumVersionIdOrderByCode(curriculumVersionId)) {
            counts = merge(counts, treeCounts(subject.knowledgeNodeId()));
        }
        int outstanding = counts.getOrDefault(KnowledgeNode.ValidationStatus.SUGGESTED, 0)
                + counts.getOrDefault(KnowledgeNode.ValidationStatus.UNVALIDATED, 0);
        if (outstanding > 0) {
            throw new ConflictException("curriculum " + version.code() + " still has "
                    + outstanding + " unvalidated node(s) — validate or reject them first");
        }
        version.activate();
        log.info("curriculum version {} ({}) activated", version.id(), version.code());
        return overviewOf(version, counts);
    }

    @Transactional
    public CurriculumOverview archiveVersion(UUID curriculumVersionId) {
        CurriculumVersion version = curriculumVersions.findById(curriculumVersionId)
                .orElseThrow(() -> new NotFoundException("curriculum version", curriculumVersionId));
        version.archive();
        return overviewOf(version, treeCountsAllSubjects(version));
    }

    private KnowledgeNode node(UUID nodeId) {
        return knowledgeNodes.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("knowledge node", nodeId));
    }

    private UUID parentIdOf(KnowledgeNode node) {
        return knowledgeEdges.findBySourceIdAndRelationType(node.id(), RelationType.PART_OF)
                .map(edge -> edge.targetId())
                .orElse(null);
    }

    private Map<KnowledgeNode.ValidationStatus, Integer> treeCountsAllSubjects(
            CurriculumVersion version) {
        Map<KnowledgeNode.ValidationStatus, Integer> counts = Map.of();
        for (Subject subject : subjects.findByCurriculumVersionIdOrderByCode(version.id())) {
            counts = merge(counts, treeCounts(subject.knowledgeNodeId()));
        }
        return counts;
    }

    private Map<KnowledgeNode.ValidationStatus, Integer> treeCounts(UUID rootId) {
        if (rootId == null) {
            return Map.of();
        }
        Map<KnowledgeNode.ValidationStatus, Integer> counts =
                new EnumMap<>(KnowledgeNode.ValidationStatus.class);
        for (UUID nodeId : knowledgeNodes.findSubtreeIds(rootId)) {
            KnowledgeNode node = knowledgeNodes.findById(nodeId).orElse(null);
            if (node != null) {
                counts.merge(node.validationStatus(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private CurriculumOverview overviewOf(CurriculumVersion version,
                                          Map<KnowledgeNode.ValidationStatus, Integer> counts) {
        return new CurriculumOverview(version.id(), version.board(), version.qualification(),
                version.code(), version.title(), version.status().name(),
                counts.getOrDefault(KnowledgeNode.ValidationStatus.VALIDATED, 0),
                counts.getOrDefault(KnowledgeNode.ValidationStatus.SUGGESTED, 0),
                counts.getOrDefault(KnowledgeNode.ValidationStatus.UNVALIDATED, 0));
    }

    private static Map<KnowledgeNode.ValidationStatus, Integer> merge(
            Map<KnowledgeNode.ValidationStatus, Integer> left,
            Map<KnowledgeNode.ValidationStatus, Integer> right) {
        Map<KnowledgeNode.ValidationStatus, Integer> merged = left.isEmpty()
                ? new EnumMap<>(KnowledgeNode.ValidationStatus.class)
                : new EnumMap<>(left);
        right.forEach((k, v) -> merged.merge(k, v, Integer::sum));
        return merged;
    }

    /**
     * @param id                curriculum version id
     * @param board             e.g. Edexcel
     * @param qualification     e.g. IAL
     * @param code              e.g. IAL-CHEM-2018
     * @param title             display title
     * @param status            DRAFT / ACTIVE / ARCHIVED
     * @param validatedNodes    KG tree nodes in VALIDATED state
     * @param suggestedNodes    nodes awaiting review (machine-suggested)
     * @param unvalidatedNodes  nodes rejected or never reviewed
     */
    public record CurriculumOverview(UUID id, String board, String qualification, String code,
                                     String title, String status, int validatedNodes,
                                     int suggestedNodes, int unvalidatedNodes) {
    }

    /**
     * @param id               node id
     * @param code             namespaced KG code
     * @param nodeType         SUBJECT/UNIT/TOPIC/SUBTOPIC
     * @param title            node title
     * @param validationStatus UNVALIDATED/SUGGESTED/VALIDATED
     * @param provenance       draft fingerprint (§17)
     * @param parentId         PART_OF parent node id
     */
    public record NodeView(UUID id, String code, String nodeType, String title,
                           String validationStatus, String provenance, UUID parentId) {

        static NodeView from(KnowledgeNode node, UUID parentId) {
            return new NodeView(node.id(), node.code(), node.nodeType().name(), node.title(),
                    node.validationStatus().name(), node.provenance(), parentId);
        }
    }
}
