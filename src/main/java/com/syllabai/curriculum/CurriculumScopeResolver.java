package com.syllabai.curriculum;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;

/**
 * Resolves the active {@link CurriculumScope} for the serving paths (T-C07).
 *
 * <p>Resolution policy — deterministic, read-only, fail-closed:</p>
 * <ol>
 *   <li>Candidate curricula: {@code curriculum_versions} with status ACTIVE.
 *       DRAFT/ARCHIVED versions never own a serving scope (the two DRAFT
 *       ingest stubs in production are excluded by construction).</li>
 *   <li>A candidate <em>owns serving surface</em> when any of its subjects has
 *       a KG subject root whose PART_OF subtree contains at least one VALIDATED
 *       structure node (the KG intent surface), <em>or</em> owns at least one
 *       exam paper (the chunk surface). A subject without a root node is not an
 *       owner on the KG side — unresolvable curriculum is invisible, never
 *       served (the CLA house rule, generalized).</li>
 *   <li>Exactly one owner → its scope. Zero owners, or two or more, → empty:
 *       an ambiguous learner scope is an UNRESOLVED scope, and unresolved means
 *       refuse — never serve across curricula. When a second curriculum gains
 *       serving surface this resolver stops resolving until a per-learner
 *       curriculum selector lands (the deliberate forcing function; per-learner
 *       assignment does not exist in the schema yet — the learnerId hook below
 *       is where it plugs in).</li>
 * </ol>
 *
 * <p>Production reality at implementation time (DB-verified 2026-09-17): two
 * ACTIVE versions ({@code 4CH1-2017}, {@code IAL-CHEM-2018}); only 4CH1-2017
 * owns surface (226 VALIDATED structure nodes under its subject root; 91 papers
 * carrying all 2,333 chunks), so the resolver yields exactly the 4CH1 scope and
 * every serving path narrows to it.</p>
 */
@Service
public class CurriculumScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(CurriculumScopeResolver.class);

    /**
     * Pinned explicit structure-type list (mirrors
     * {@code KnowledgeNodeRepository.findStructureNodes}; the V15 rule that the
     * CONCEPT family can never leak into the curriculum structure surface).
     */
    private static final List<NodeType> STRUCTURE_NODE_TYPES =
            List.of(NodeType.UNIT, NodeType.TOPIC, NodeType.SUBTOPIC);

    private final CurriculumVersionRepository curriculumVersions;
    private final SubjectRepository subjects;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final ExamPaperRepository examPapers;

    public CurriculumScopeResolver(CurriculumVersionRepository curriculumVersions,
                                   SubjectRepository subjects,
                                   KnowledgeNodeRepository knowledgeNodes,
                                   ExamPaperRepository examPapers) {
        this.curriculumVersions = curriculumVersions;
        this.subjects = subjects;
        this.knowledgeNodes = knowledgeNodes;
        this.examPapers = examPapers;
    }

    /**
     * Resolves the serving scope. {@code learnerId} is reserved for the future
     * per-learner curriculum selector (no learner→curriculum mapping exists in
     * the schema yet); today the resolution is learner-independent by design —
     * anonymous preview and learner asks scope identically.
     *
     * @param learnerId the asking learner (reserved; may be null for preview)
     * @return the active scope, or empty when unresolved (zero or ambiguous
     *         owners) — callers must refuse, never serve unscoped
     */
    public Optional<CurriculumScope> resolveActive(UUID learnerId) {
        List<CurriculumVersion> active = curriculumVersions
                .findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status.ACTIVE);
        List<CurriculumVersion> owners = active.stream().filter(this::ownsSurface).toList();
        if (owners.size() != 1) {
            log.info("curriculum scope unresolved: {} ACTIVE candidate(s), {} owner(s) — refusing over serving",
                    active.size(), owners.size());
            return Optional.empty();
        }
        CurriculumVersion version = owners.getFirst();
        Set<UUID> surface = intentSurface(version);
        log.debug("curriculum scope resolved: {} ({} surface node(s))", version.code(), surface.size());
        return Optional.of(new CurriculumScope(version.id(), version.code(), surface));
    }

    /** KG intent surface: union of the PART_OF subtrees under the version's subject roots. */
    private Set<UUID> intentSurface(CurriculumVersion version) {
        Set<UUID> surface = new LinkedHashSet<>();
        for (Subject subject : subjects.findByCurriculumVersionIdOrderByCode(version.id())) {
            UUID rootId = subject.knowledgeNodeId();
            if (rootId != null) {
                surface.addAll(knowledgeNodes.findSubtreeIds(rootId));
            }
        }
        return surface;
    }

    /** Ownership test: KG intent surface (VALIDATED structure) or exam-paper surface. */
    private boolean ownsSurface(CurriculumVersion version) {
        for (Subject subject : subjects.findByCurriculumVersionIdOrderByCode(version.id())) {
            UUID rootId = subject.knowledgeNodeId();
            if (rootId != null && hasValidatedStructure(rootId)) {
                return true;
            }
            if (examPapers.existsBySubjectId(subject.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ownership test: KG intent surface (VALIDATED structure) or exam-paper surface.
     *
     * <p>T-C32: the VALIDATED-structure question is answered with ONE batched
     * predicate over the subtree id set (the id set still comes from
     * {@code findSubtreeIds}, the single source of truth). Before, this loop
     * issued one {@code findById} per subtree node — ~340 reads per search,
     * per subject, per ACTIVE version, on every serving request. The evaluated
     * predicate is unchanged: a subtree owns KG surface iff it contains a
     * UNIT/TOPIC/SUBTOPIC node whose validation is VALIDATED; an empty subtree
     * fails closed without touching the repository predicate.</p>
     */
    private boolean hasValidatedStructure(UUID subjectRootId) {
        List<UUID> subtreeIds = knowledgeNodes.findSubtreeIds(subjectRootId);
        if (subtreeIds.isEmpty()) {
            return false;
        }
        return knowledgeNodes.existsByIdInAndNodeTypeInAndValidationStatus(
                subtreeIds, STRUCTURE_NODE_TYPES, KnowledgeNode.ValidationStatus.VALIDATED);
    }
}
