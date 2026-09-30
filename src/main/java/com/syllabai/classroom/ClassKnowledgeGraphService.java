package com.syllabai.classroom;

import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassGraphEdgeView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassGraphNodeView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassNodeStudentView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassNodeStudentsView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassKnowledgeGraphView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.StudentEvidenceItemView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.StudentMisconceptionView;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.learner.LearnerKnowledgeGraphService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.decay.DecayParams;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The F-072 class knowledge-graph heatmap read model: the curriculum subtree
 * composed with class-level learner aggregation (TEACHER_ARCHITECTURE §13.3)
 * and the TFA-03 teaching-coverage overlay (§13.4) — the taught/not-taught ×
 * understanding matrix in one honest payload.
 *
 * <p><b>The independent-student rule (non-negotiable).</b> The aggregation
 * roster is EXACTLY this class's ENABLED {@code class_members} rows — a
 * student without a membership row in this class never moves a single
 * aggregated number, no matter what their learner model says. A disabled
 * member's account drops out the same way (the assignment-roster rule).</p>
 *
 * <p><b>Evidence semantics.</b> "Aggregate student KGs" means aggregating
 * what the student KG actually shows: per-student EFFECTIVE (decayed) mastery
 * evaluated at one pinned {@code asOf} instant, banded by the SAME shared
 * vocabulary every learner-facing surface uses ({@link DecayParams#bandOf}).
 * The cell deliberately exposes the §13.3 distribution, not just the mean —
 * a polarized class must not hide behind an average. Unmeasured is honest:
 * {@code learnersMeasured = 0}, null mean, UNMEASURED band, zero counts —
 * nothing fabricated.</p>
 *
 * <p><b>Coverage semantics.</b> Spec points report their recorded row
 * verbatim ("taught" | "not-taught" | "unrecorded"); every other node
 * DERIVES from its descendant spec-point rows with the counts exposed. Grey
 * on the graph means absent teaching coverage — never low understanding
 * (the TFA-03 ruling this feature exists to render).</p>
 *
 * <p><b>Read-only by construction, batched by construction.</b> One tree
 * query, one roster query, then ONE query per evidence table for the whole
 * roster × scope — no per-learner or per-node loop touches the database.
 * Nothing here writes: the heatmap consumes the learner model and the
 * coverage overlay, it never becomes a second implementation of either.</p>
 *
 * <p><b>TFA-07 drill-down (TEACHER_ARCHITECTURE §13.5 + §14).</b> The same
 * read model serves the drill chain's next legs: a node's affected students
 * ({@link #nodeStudents}) — the heatmap's roster + band vocabulary at
 * student grain, with the raw attempts behind the numbers — and the
 * teacher-authorized individual student graph ({@link #learnerKnowledgeGraph}),
 * which deliberately DELEGATES to {@link LearnerKnowledgeGraphService} (F-034)
 * so there is exactly ONE student-graph implementation; the teacher lens adds
 * only the §17 gates, never a second graph.</p>
 */
@Service
public class ClassKnowledgeGraphService {

    /** most recent attempts scanned per node-students read, before grouping */
    private static final int ATTEMPT_SCAN_CAP = 120;
    /** recent attempts kept per student in the node detail panel */
    private static final int EVIDENCE_PER_STUDENT = 3;

    private final KnowledgeGraphService graph;
    private final SkillStateRepository skillStates;
    private final MisconceptionStateRepository misconceptionStates;
    private final TeachingCoverageRepository coverage;
    private final ClassMemberRepository members;
    private final UserRepository users;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties learnerProperties;
    private final AttemptRepository attempts;
    private final LearnerKnowledgeGraphService learnerGraphs;

    public ClassKnowledgeGraphService(KnowledgeGraphService graph,
                                      SkillStateRepository skillStates,
                                      MisconceptionStateRepository misconceptionStates,
                                      TeachingCoverageRepository coverage,
                                      ClassMemberRepository members,
                                      UserRepository users,
                                      EbbinghausDecayService decayService,
                                      LearnerProperties learnerProperties,
                                      AttemptRepository attempts,
                                      LearnerKnowledgeGraphService learnerGraphs) {
        this.graph = graph;
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.coverage = coverage;
        this.members = members;
        this.users = users;
        this.decayService = decayService;
        this.learnerProperties = learnerProperties;
        this.attempts = attempts;
        this.learnerGraphs = learnerGraphs;
    }

    /**
     * The class KG heatmap for one owned class and one subject root.
     * Ownership and existence gates run in the controller (the §17 house
     * pattern); this service trusts its caller and stays read-only.
     */
    @Transactional(readOnly = true)
    public ClassKnowledgeGraphView graph(SchoolClass clazz, UUID rootId) {
        Instant now = Instant.now(); // ONE clock read: the decay + provenance anchor
        DecayParams decayParams = learnerProperties.decay().toParams();

        NodeView tree = graph.treeWithMisconceptions(rootId);

        // the roster: exactly this class's enabled members (the independent-
        // student gate — membership rows are the single source of class scope)
        List<UUID> memberIds = members.findByClassIdOrderByEnrolledAtAsc(clazz.id())
                .stream().map(ClassMember::studentId).toList();
        List<UUID> roster = users.findAllById(memberIds).stream()
                .filter(User::enabled)
                .map(User::id)
                .sorted()
                .toList();

        // the scope: the PART_OF subtree in curriculum order + misconception registry
        Map<UUID, NodeView> structureById = new LinkedHashMap<>();
        Map<UUID, List<NodeView>> childrenOf = new HashMap<>();
        Map<UUID, List<NodeView>> misconceptionsOf = new HashMap<>();
        java.util.Set<UUID> miscoNodeIds = new java.util.HashSet<>();
        collect(tree, childrenOf, structureById, misconceptionsOf, miscoNodeIds);

        // one batched query per evidence table for the whole roster × scope
        Map<UUID, List<SkillState>> statesByNode = new HashMap<>();
        if (!roster.isEmpty()) {
            for (SkillState s : skillStates.findByLearnerIdInAndNodeIdIn(
                    roster, structureById.keySet())) {
                statesByNode.computeIfAbsent(s.nodeId(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<UUID, List<MisconceptionState>> miscoByNode = new HashMap<>();
        if (!roster.isEmpty() && !miscoNodeIds.isEmpty()) {
            // keyed by the MISCONCEPTION node ids — never the parent topic ids
            for (MisconceptionState m : misconceptionStates
                    .findByLearnerIdInAndMisconceptionNodeIdIn(roster, miscoNodeIds)) {
                miscoByNode.computeIfAbsent(m.misconceptionNodeId(), k -> new ArrayList<>())
                        .add(m);
            }
        }

        // the recorded coverage rows for this class (absence = unrecorded)
        Map<UUID, TeachingCoverage> coverageRows = new HashMap<>();
        for (TeachingCoverage row : coverage
                .findByClassIdOrderBySpecPointNodeIdAsc(clazz.id())) {
            coverageRows.put(row.specPointNodeId(), row);
        }

        // coverage counts per node, memoized bottom-up (descendant-or-self)
        Map<UUID, int[]> coverageCounts = new HashMap<>();
        for (UUID id : structureById.keySet()) {
            coverageCounts(id, structureById, childrenOf, coverageRows, coverageCounts);
        }

        double activeThreshold = learnerProperties.bdt().activeThreshold();

        List<ClassGraphNodeView> nodes = new ArrayList<>();
        for (NodeView node : structureById.values()) {
            nodes.add(nodeView(node, structureById, childrenOf, misconceptionsOf,
                    statesByNode, miscoByNode, coverageRows, coverageCounts,
                    decayParams, activeThreshold, now));
        }

        List<ClassGraphEdgeView> edges = new ArrayList<>();
        for (PrerequisiteRelation relation : graph.prerequisiteRelations(rootId)) {
            // structural invariant (the student-KG rule): prerequisiteRelations
            // operates on the PART_OF subtree — unknown ids are graph
            // inconsistencies, skipped, never guessed; codes come from the
            // node registry, the relation record carries ids only
            NodeView prerequisite = structureById.get(relation.prerequisiteId());
            NodeView dependent = structureById.get(relation.dependentNodeId());
            if (prerequisite != null && dependent != null) {
                edges.add(new ClassGraphEdgeView(
                        prerequisite.id(), prerequisite.code(),
                        dependent.id(), dependent.code()));
            }
        }

        return new ClassKnowledgeGraphView(
                clazz.id(), clazz.name(), tree.id(), tree.code(), tree.title(),
                roster.size(), now, List.copyOf(nodes), List.copyOf(edges));
    }

    /**
     * The §13.5 node detail: one node's affected students for an owned class
     * — the drill chain's "weak node → affected students" leg. The roster is
     * exactly the heatmap's (enabled members only — the independent-student
     * rule), each row carries the SAME per-node semantics the student KG
     * shows that student (raw + effective mastery, shared band vocabulary),
     * plus their misconception estimates on nodes attached under this node
     * and a bounded slice of their recent attempts mapped to this node.
     * Weakest measured students sort first; unmeasured students sit at the
     * end with honest nulls — the panel must not fabricate.
     *
     * <p>Gates: the root must exist (the graph read service throws 404) and
     * the node must belong to the root's PART_OF subtree — a node outside
     * the subject is a 404 (subject isolation), never a silent cross-subject
     * hop. Archived classes stay readable, exactly like the heatmap.</p>
     */
    @Transactional(readOnly = true)
    public ClassNodeStudentsView nodeStudents(SchoolClass clazz, UUID rootId, UUID nodeId) {
        Instant now = Instant.now(); // ONE clock read: decay + provenance anchor
        DecayParams decayParams = learnerProperties.decay().toParams();
        double activeThreshold = learnerProperties.bdt().activeThreshold();

        NodeView tree = graph.treeWithMisconceptions(rootId);

        // the scope: the PART_OF subtree + misconception registry (the heatmap walk)
        Map<UUID, NodeView> structureById = new LinkedHashMap<>();
        Map<UUID, List<NodeView>> childrenOf = new HashMap<>();
        Map<UUID, List<NodeView>> misconceptionsOf = new HashMap<>();
        java.util.Set<UUID> miscoNodeIds = new java.util.HashSet<>();
        collect(tree, childrenOf, structureById, misconceptionsOf, miscoNodeIds);
        NodeView node = structureById.get(nodeId);
        if (node == null) {
            // not under this root (or not a structure node) — subject isolation
            throw new NotFoundException("node is not part of this subject subtree");
        }

        // the roster: exactly this class's enabled members (the heatmap's gate)
        List<UUID> memberIds = members.findByClassIdOrderByEnrolledAtAsc(clazz.id())
                .stream().map(ClassMember::studentId).toList();
        Map<UUID, String> names = new HashMap<>();
        List<UUID> roster = new ArrayList<>();
        if (!memberIds.isEmpty()) {
            for (User u : users.findAllById(memberIds)) {
                if (u.enabled()) {
                    roster.add(u.id());
                    names.put(u.id(), u.displayName());
                }
            }
        }

        // one batched query per evidence table, whole roster × this node
        Map<UUID, SkillState> stateByLearner = new HashMap<>();
        if (!roster.isEmpty()) {
            for (SkillState s : skillStates.findByLearnerIdInAndNodeIdIn(
                    roster, Set.of(nodeId))) {
                stateByLearner.put(s.learnerId(), s);
            }
        }
        List<NodeView> nodeMisconceptions =
                misconceptionsOf.getOrDefault(nodeId, List.of());
        Map<UUID, List<MisconceptionState>> miscoByLearner = new HashMap<>();
        if (!roster.isEmpty() && !nodeMisconceptions.isEmpty()) {
            Set<UUID> nodeMiscoIds = nodeMisconceptions.stream()
                    .map(NodeView::id).collect(Collectors.toSet());
            for (MisconceptionState m : misconceptionStates
                    .findByLearnerIdInAndMisconceptionNodeIdIn(roster, nodeMiscoIds)) {
                miscoByLearner.computeIfAbsent(m.learnerId(), k -> new ArrayList<>()).add(m);
            }
        }
        Map<UUID, List<Attempt>> recentByLearner = new HashMap<>();
        if (!roster.isEmpty()) {
            for (Attempt a : attempts.findRecentByTopicNodeAndLearnerIdIn(
                    nodeId, roster, PageRequest.of(0, ATTEMPT_SCAN_CAP))) {
                List<Attempt> slice = recentByLearner.computeIfAbsent(
                        a.learnerId(), k -> new ArrayList<>());
                if (slice.size() < EVIDENCE_PER_STUDENT) {
                    slice.add(a);
                }
            }
        }

        // coverage state for THIS node, the heatmap's own derivation
        Map<UUID, TeachingCoverage> coverageRows = new HashMap<>();
        for (TeachingCoverage row : coverage
                .findByClassIdOrderBySpecPointNodeIdAsc(clazz.id())) {
            coverageRows.put(row.specPointNodeId(), row);
        }
        Map<UUID, int[]> coverageMemo = new HashMap<>();
        int[] counts = coverageCounts(nodeId, structureById, childrenOf,
                coverageRows, coverageMemo);
        String coverageState;
        if (isSpecPoint(node)) {
            TeachingCoverage row = coverageRows.get(node.id());
            coverageState = row == null ? "unrecorded" : row.status().wire();
        } else {
            coverageState = counts[2] > 0 ? "taught"
                    : counts[1] > 0 ? "not-taught" : "unrecorded";
        }

        // the §13.3 band distribution, restated from the same states the rows show
        int struggling = 0;
        int developing = 0;
        int proficient = 0;
        for (SkillState s : stateByLearner.values()) {
            double effective = decayService.decayed(
                    s.mastery(), s.lastPracticedAt(), now, decayParams);
            switch (decayParams.bandOf(effective)) {
                case "LOW" -> struggling++;
                case "DEVELOPING" -> developing++;
                case "SECURE" -> proficient++;
                default -> throw new IllegalStateException(
                        "unexpected mastery band: " + decayParams.bandOf(effective));
            }
        }

        List<ClassNodeStudentView> students = new ArrayList<>();
        for (UUID learnerId : roster) {
            SkillState s = stateByLearner.get(learnerId);
            Double mastery = null;
            Double effective = null;
            String band = null;
            Integer attemptCount = null;
            Integer correctCount = null;
            Instant lastPracticed = null;
            if (s != null) {
                mastery = s.mastery();
                effective = decayService.decayed(
                        s.mastery(), s.lastPracticedAt(), now, decayParams);
                band = decayParams.bandOf(effective);
                attemptCount = s.attempts();
                correctCount = s.correctCount();
                lastPracticed = s.lastPracticedAt();
            }
            List<StudentMisconceptionView> misconceptions = new ArrayList<>();
            for (MisconceptionState m : miscoByLearner.getOrDefault(learnerId, List.of())) {
                NodeView miscoNode = nodeMisconceptions.stream()
                        .filter(nv -> nv.id().equals(m.misconceptionNodeId()))
                        .findFirst().orElse(null);
                // structural invariant: the state's node came from THIS node's
                // attached misconception set — a miss would be a graph
                // inconsistency, so it is skipped, not guessed
                if (miscoNode == null) {
                    continue;
                }
                misconceptions.add(new StudentMisconceptionView(
                        miscoNode.id(), miscoNode.code(), miscoNode.title(),
                        m.probability(), m.probability() >= activeThreshold));
            }
            List<StudentEvidenceItemView> evidence = new ArrayList<>();
            for (Attempt a : recentByLearner.getOrDefault(learnerId, List.of())) {
                evidence.add(new StudentEvidenceItemView(
                        a.id(), a.question().id(), a.question().externalRef(),
                        a.correct(), a.marksAwarded(), a.question().marks(),
                        a.markingState().name(), a.createdAt()));
            }
            students.add(new ClassNodeStudentView(
                    learnerId, names.getOrDefault(learnerId, "unknown"),
                    mastery, effective, band, attemptCount, correctCount,
                    lastPracticed, List.copyOf(misconceptions), List.copyOf(evidence)));
        }
        // weakest first (the teacher opens this panel BECAUSE the node is weak),
        // unmeasured last, then display name, then id — deterministic
        students.sort(Comparator
                .comparing((ClassNodeStudentView v) -> v.effectiveMastery() == null)
                .thenComparing((ClassNodeStudentView v) -> v.effectiveMastery() == null
                        ? 0.0 : v.effectiveMastery().doubleValue())
                .thenComparing((ClassNodeStudentView v) -> v.displayName() == null
                        ? "" : v.displayName().toLowerCase())
                .thenComparing((ClassNodeStudentView v) -> v.learnerId().toString()));

        return new ClassNodeStudentsView(
                clazz.id(), clazz.name(), rootId, node.id(), node.code(), node.title(),
                node.type(), coverageState, roster.size(),
                struggling, developing, proficient, now, List.copyOf(students));
    }

    /**
     * The §14 teacher view of ONE student's subject graph: the SAME F-034
     * read model the student themselves sees (one graph implementation —
     * this method only adds the §17 gates, never a second KG), reachable by
     * the teacher ONLY for learners who are ENABLED members of THIS class.
     * A non-member is a 404 — the roster is the privacy boundary, and an
     * absent membership neither confirms nor denies any other enrollment.
     */
    @Transactional(readOnly = true)
    public LearnerKnowledgeGraphView learnerKnowledgeGraph(SchoolClass clazz,
                                                           UUID learnerId,
                                                           UUID rootId) {
        if (!members.existsByClassIdAndStudentId(clazz.id(), learnerId)
                || users.findById(learnerId).filter(User::enabled).isEmpty()) {
            throw new NotFoundException("learner is not a member of this class");
        }
        return learnerGraphs.graphFor(learnerId, rootId);
    }

    // ═══════════════════════════ internals ═══════════════════════════

    private void collect(NodeView node,
                         Map<UUID, List<NodeView>> childrenOf,
                         Map<UUID, NodeView> structureById,
                         Map<UUID, List<NodeView>> misconceptionsOf,
                         java.util.Set<UUID> miscoNodeIds) {
        if ("MISCONCEPTION".equals(node.type())) {
            return; // misconception nodes are registered under their parent, below
        }
        structureById.put(node.id(), node);
        List<NodeView> structureChildren = new ArrayList<>();
        for (NodeView child : node.children()) {
            if ("MISCONCEPTION".equals(child.type())) {
                misconceptionsOf.computeIfAbsent(node.id(), k -> new ArrayList<>()).add(child);
                miscoNodeIds.add(child.id());
            } else {
                structureChildren.add(child);
                collect(child, childrenOf, structureById, misconceptionsOf, miscoNodeIds);
            }
        }
        childrenOf.put(node.id(), List.copyOf(structureChildren));
    }

    /**
     * {specPoints, recordedSpecPoints, taughtSpecPoints} over the node and
     * its descendants, memoized. A spec point is the V39 invariant:
     * SUBTOPIC-typed with non-null applicability — the same gate the
     * coverage marking contract uses, so coverage and heatmap can never
     * disagree about what counts as a specification point.
     */
    private int[] coverageCounts(UUID nodeId,
                                 Map<UUID, NodeView> structureById,
                                 Map<UUID, List<NodeView>> childrenOf,
                                 Map<UUID, TeachingCoverage> coverageRows,
                                 Map<UUID, int[]> memo) {
        int[] cached = memo.get(nodeId);
        if (cached != null) {
            return cached;
        }
        NodeView node = structureById.get(nodeId);
        int[] counts = isSpecPoint(node) ? new int[]{1, 0, 0} : new int[]{0, 0, 0};
        TeachingCoverage row = coverageRows.get(nodeId);
        if (row != null) {
            counts[1] += 1;
            if (row.status() == TeachingCoverage.Status.TAUGHT) {
                counts[2] += 1;
            }
        }
        for (NodeView child : childrenOf.getOrDefault(nodeId, List.of())) {
            int[] childCounts = coverageCounts(child.id(), structureById, childrenOf,
                    coverageRows, memo);
            counts[0] += childCounts[0];
            counts[1] += childCounts[1];
            counts[2] += childCounts[2];
        }
        memo.put(nodeId, counts);
        return counts;
    }

    private ClassGraphNodeView nodeView(NodeView node,
                                        Map<UUID, NodeView> structureById,
                                        Map<UUID, List<NodeView>> childrenOf,
                                        Map<UUID, List<NodeView>> misconceptionsOf,
                                        Map<UUID, List<SkillState>> statesByNode,
                                        Map<UUID, List<MisconceptionState>> miscoByNode,
                                        Map<UUID, TeachingCoverage> coverageRows,
                                        Map<UUID, int[]> coverageCounts,
                                        DecayParams decayParams,
                                        double activeThreshold,
                                        Instant now) {
        List<NodeView> children = childrenOf.getOrDefault(node.id(), List.of());
        List<UUID> childIds = children.stream().map(NodeView::id).toList();
        boolean specPoint = isSpecPoint(node);

        // coverage: spec points verbatim; other nodes derived from descendants
        String coverageState;
        if (specPoint) {
            TeachingCoverage row = coverageRows.get(node.id());
            coverageState = row == null ? "unrecorded" : row.status().wire();
        } else {
            int[] counts = coverageCounts.get(node.id());
            coverageState = counts[2] > 0 ? "taught"
                    : counts[1] > 0 ? "not-taught" : "unrecorded";
        }
        int[] counts = coverageCounts.get(node.id());

        // class understanding: effective (decayed) mastery per measured member,
        // mean + distribution by the shared band vocabulary (§13.3)
        List<SkillState> states = statesByNode.getOrDefault(node.id(), List.of());
        int learnersMeasured = states.size();
        Double meanMastery = null;
        String meanBand = "UNMEASURED";
        int struggling = 0;
        int developing = 0;
        int proficient = 0;
        if (learnersMeasured > 0) {
            double total = 0;
            for (SkillState s : states) {
                double effective = decayService.decayed(
                        s.mastery(), s.lastPracticedAt(), now, decayParams);
                total += effective;
                switch (decayParams.bandOf(effective)) {
                    case "LOW" -> struggling++;
                    case "DEVELOPING" -> developing++;
                    case "SECURE" -> proficient++;
                    default -> {
                        // the band vocabulary is exactly these three; anything
                        // else would mean config drift — refuse to bucket it
                        // rather than misfile a student
                        throw new IllegalStateException(
                                "unexpected mastery band: " + decayParams.bandOf(effective));
                    }
                }
            }
            meanMastery = round(total / learnersMeasured);
            meanBand = decayParams.bandOf(meanMastery);
        }

        // misconception prevalence: distinct members with an ACTIVE (BDT)
        // estimate on any misconception node attached under this node
        int learnersWithActiveMisconception = 0;
        if (!miscoByNode.isEmpty()) {
            java.util.Set<UUID> affected = new java.util.HashSet<>();
            for (NodeView misco : misconceptionsOf.getOrDefault(node.id(), List.of())) {
                for (MisconceptionState m : miscoByNode.getOrDefault(misco.id(), List.of())) {
                    if (m.probability() >= activeThreshold) {
                        affected.add(m.learnerId());
                    }
                }
            }
            learnersWithActiveMisconception = affected.size();
        }

        int attempts = states.stream().mapToInt(SkillState::attempts).sum();
        int correct = states.stream().mapToInt(SkillState::correctCount).sum();

        return new ClassGraphNodeView(
                node.id(), node.code(), node.type(), node.title(), node.description(),
                childIds,
                coverageState,
                counts[0], counts[1], counts[2],
                learnersMeasured, meanMastery, meanBand,
                struggling, developing, proficient,
                attempts, correct,
                learnersWithActiveMisconception);
    }

    /** the V39 invariant as a predicate — the exact spec-point gate */
    private static boolean isSpecPoint(NodeView node) {
        return "SUBTOPIC".equals(node.type()) && node.applicability() != null;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
