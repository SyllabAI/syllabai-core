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
 *
 * <p>V53 per-course resolution (ADR-030): {@link #resolveForCourse(String)}
 * resolves an explicitly course-tagged ask against core's OWN registry —
 * exact {@code curriculum_versions.code} match, ACTIVE only, the SAME
 * ownsSurface test. Exactly one owner serves; zero or ambiguous refuse.
 * The ref itself is opaque hub-owned data stored verbatim on the session
 * (the V47/V48/V49/V51 ruling) — this method is a core-native lookup
 * against {@code curriculum_versions}, never a hub-registry parse. The
 * global {@link #resolveActive} path is untouched: an ask WITHOUT a course
 * ref resolves exactly as before (pilot compatibility is a design decision,
 * not a fallback), and the per-learner selector hook stays reserved.</p>
 */
@Service
public class CurriculumScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(CurriculumScopeResolver.class);

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
        return singleOwnerScope(active,
                "the ACTIVE set (" + active.size() + " candidate(s))");
    }

    /**
     * V53 per-course resolution (ADR-030): resolves a hub-supplied course
     * reference to the ONE curriculum it names. Exact {@code code} match
     * among ACTIVE versions (a ref that does not exactly name a core
     * curriculum is unresolved — no prefix, no normalization, no fuzzy
     * variants: the two code namespaces have no derivable rule between
     * them, so matching beyond exact equality would be a hidden registry),
     * then the SAME {@code ownsSurface} test the global path applies.
     *
     * @param courseRef the opaque hub-supplied course reference (never
     *                  parsed by core beyond this exact-code lookup)
     * @return exactly one owner's scope; empty when the ref is blank, names
     *         no ACTIVE curriculum, or names an ambiguous/non-serving one —
     *         callers must refuse deterministically, never fall back to the
     *         global scope (a wrong-corpus answer is worse than a refusal)
     */
    public Optional<CurriculumScope> resolveForCourse(String courseRef) {
        if (courseRef == null || courseRef.isBlank()) {
            return Optional.empty();
        }
        String ref = courseRef.strip();
        List<CurriculumVersion> candidates =
                curriculumVersions.findByCodeAndStatus(ref, CurriculumVersion.Status.ACTIVE);
        return singleOwnerScope(candidates, "course ref [" + ref + "]");
    }

    /** Shared fail-closed tail of both resolution paths: exactly one
     *  surface-owning candidate serves; anything else refuses. */
    private Optional<CurriculumScope> singleOwnerScope(List<CurriculumVersion> candidates,
                                                       String describe) {
        List<CurriculumVersion> owners = candidates.stream().filter(this::ownsSurface).toList();
        if (owners.size() != 1) {
            log.info("curriculum scope unresolved for {}: {} candidate(s), {} owner(s)"
                            + " — refusing over serving",
                    describe, candidates.size(), owners.size());
            return Optional.empty();
        }
        CurriculumVersion version = owners.getFirst();
        Set<UUID> surface = intentSurface(version);
        log.debug("curriculum scope resolved for {}: {} ({} surface node(s))",
                describe, version.code(), surface.size());
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

    private boolean hasValidatedStructure(UUID subjectRootId) {
        for (UUID nodeId : knowledgeNodes.findSubtreeIds(subjectRootId)) {
            KnowledgeNode node = knowledgeNodes.findById(nodeId).orElse(null);
            if (node == null) {
                continue;
            }
            boolean structure = node.nodeType() == com.syllabai.knowledge.NodeType.UNIT
                    || node.nodeType() == com.syllabai.knowledge.NodeType.TOPIC
                    || node.nodeType() == com.syllabai.knowledge.NodeType.SUBTOPIC;
            // VALIDATED-only: SUGGESTED/UNVALIDATED seeds are invisible to
            // serving (§7 gate, mirrors the retriever + ServableQuestionSpec)
            if (structure
                    && node.validationStatus() == KnowledgeNode.ValidationStatus.VALIDATED) {
                return true;
            }
        }
        return false;
    }
}
