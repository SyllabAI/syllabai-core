package com.syllabai.cla;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side ResourceContext resolution (CLA contract §1, §5): the client
 * passes opaque references; the server resolves them fail-closed.
 *
 * <p>Resolution order (contract §5): resolve context → validate gate → scope.
 * Concretely:</p>
 * <ol>
 *   <li>the root must BE a subject root — CLA v1 requires a subject-rooted
 *       context, which is what makes curriculum identity and subject
 *       isolation resolvable server-side;</li>
 *   <li>the topic must live inside the root's PART_OF subtree (hard subject
 *       isolation — the same rule as the Smart Lesson surface; a topic from
 *       another subject is a 404, not a silent cross-subject hop);</li>
 *   <li>the validation gate is the serving boundary's own rule: only
 *       VALIDATED curriculum nodes may anchor CLA evidence assembly
 *       (SUGGESTED/UNVALIDATED content is invisible to the CLA exactly as it
 *       is to the Tutor — and fails indistinguishably from an unresolvable
 *       reference, so no validation-state oracle is created);</li>
 *   <li>curriculum identity comes from the owning subject's version, never
 *       from the client.</li>
 * </ol>
 */
@Service
public class ClaContextResolver {

    private final KnowledgeGraphService graph;
    private final KnowledgeNodeRepository nodes;
    private final SubjectRepository subjects;
    private final QuestionRepository questions;
    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final ServableQuestionService servableQuestions;
    private final AttemptRepository attempts;

    public ClaContextResolver(KnowledgeGraphService graph,
                              KnowledgeNodeRepository nodes,
                              SubjectRepository subjects,
                              QuestionRepository questions,
                              ExamPaperRepository examPapers,
                              QuestionVersionRepository questionVersions,
                              ServableQuestionService servableQuestions,
                              AttemptRepository attempts) {
        this.graph = graph;
        this.nodes = nodes;
        this.subjects = subjects;
        this.questions = questions;
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.servableQuestions = servableQuestions;
        this.attempts = attempts;
    }

    /**
     * Resolve a KG_TOPIC context. Every failure mode is a
     * {@link NotFoundException}: an unresolvable reference is a 404, never a
     * best-effort guess (contract §1.1).
     */
    @Transactional(readOnly = true)
    public ResourceContext resolveKgTopic(UUID rootId, UUID topicNodeId, UUID learnerId) {
        if (topicNodeId == null) {
            throw new BadRequestException("KG_TOPIC context requires topicNodeId");
        }
        return resolveCurriculumNode(rootId, topicNodeId, learnerId,
                ResourceContext.Kind.KG_TOPIC, "curriculum topic");
    }

    /**
     * Resolve a SPECIFICATION_POINT context (the syllabus-browser anchor: the
     * client passes the spec-point CODE it is displaying — e.g. "4CH1-1.18" —
     * an opaque string the server resolves to a validated curriculum node in
     * the rooted subject). Same fail-closed discipline as {@link #resolveKgTopic}:
     * unknown code / code outside the subject / non-VALIDATED node are all an
     * indistinguishable 404 — no existence oracle, no validation-state oracle.
     */
    @Transactional(readOnly = true)
    public ResourceContext resolveSpecificationPoint(UUID rootId, String specCode,
                                                     UUID learnerId) {
        if (specCode == null || specCode.isBlank()) {
            throw new BadRequestException("SPECIFICATION_POINT context requires specCode");
        }
        Subject subject = subjects.findByKnowledgeNodeId(rootId)
                .orElseThrow(() -> new NotFoundException("curriculum subject root", rootId));

        // registry over the subject subtree; the code must resolve INSIDE it
        // (subject isolation — a foreign subject's spec code is a 404, not a
        // best-effort guess)
        Map<UUID, NodeView> byId = new HashMap<>();
        collect(graph.tree(rootId), byId);
        String wanted = specCode.strip();
        UUID match = byId.values().stream()
                .filter(n -> wanted.equals(n.code()))
                .map(NodeView::id)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "specification point in this subject", wanted));
        return resolveCurriculumNode(rootId, match, learnerId,
                ResourceContext.Kind.SPECIFICATION_POINT, "validated specification point");
    }

    /**
     * Shared resolution spine for the curriculum-node-anchored kinds
     * (KG_TOPIC, SPECIFICATION_POINT): subtree scoping + the §1.2 validation
     * gate + curriculum identity from the owning subject. Every failure is an
     * indistinguishable 404.
     */
    private ResourceContext resolveCurriculumNode(UUID rootId, UUID nodeId,
                                                  UUID learnerId,
                                                  ResourceContext.Kind kind,
                                                  String what) {
        Subject subject = subjects.findByKnowledgeNodeId(rootId)
                .orElseThrow(() -> new NotFoundException("curriculum subject root", rootId));

        // registry over the subject subtree (same scoping pattern as Smart Lesson)
        Map<UUID, NodeView> byId = new HashMap<>();
        collect(graph.tree(rootId), byId);

        NodeView topic = byId.get(nodeId);
        if (topic == null) {
            throw new NotFoundException(what + " in this subject", nodeId);
        }

        KnowledgeNode node = nodes.findById(nodeId)
                .orElseThrow(() -> new NotFoundException(what, nodeId));
        if (node.validationStatus() != KnowledgeNode.ValidationStatus.VALIDATED) {
            // validation gate (contract §1.2): fail-closed, indistinguishable
            // from unresolvable — no validation-state existence oracle
            throw new NotFoundException("validated " + what, nodeId);
        }

        CurriculumVersion version = subject.curriculumVersion();
        return new ResourceContext(
                kind,
                nodeId,
                nodeId,
                rootId,
                subject.code(),
                node.code(),
                node.title(),
                new ResourceContext.CurriculumVersionInfo(
                        version.code(), version.board(), version.qualification(),
                        version.status().name()),
                node.validationStatus().name(),
                learnerId,
                Instant.now(),
                null, null, 0, null, null);
    }

    /**
     * Resolve a PAST_PAPER_QUESTION context (step 2, CLA contract §7). The
     * question is the anchor; its primary KG topic is the deterministic spec
     * anchor. Resolution order (contract §5): resolve → validation gate →
     * scope → attempt state:
     * <ol>
     *   <li>the question must exist and be active — else 404;</li>
     *   <li>the question must be SERVABLE through the exact serving gate
     *       ({@link ServableQuestionService#isServable} — validated current
     *       version + paper-level integrity gate) — else an indistinguishable
     *       404, no serving-state oracle;</li>
     *   <li>subject identity comes from the question's paper (server-side);
     *       the primary topic must live in that subject's subtree and be
     *       VALIDATED (the same curriculum gate as KG_TOPIC) — else 404;</li>
     *   <li>the attempt-state read (§7.3) is deterministic over attempt
     *       history — the SAME substrate as Review Hub.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public ResourceContext resolvePastPaperQuestion(UUID questionId, UUID learnerId) {
        Question question = questions.findById(questionId)
                .filter(q -> q.active())
                .orElseThrow(() -> new NotFoundException("servable question", questionId));
        if (!servableQuestions.isServable(questionId)) {
            // validation/paper-integrity gate: fail-closed, indistinguishable
            // from unresolvable — no serving-state oracle (contract §7.1/§1.2)
            throw new NotFoundException("servable question", questionId);
        }
        // paper identity may be absent for paper-less (SEED_DEMO) questions —
        // a legitimate servable anchor; the subject then resolves by
        // deterministic subtree containment of the primary topic instead of
        // the paper's subject FK
        ExamPaper paper = question.examPaperId() == null ? null
                : examPapers.findById(question.examPaperId())
                        .orElseThrow(() -> new NotFoundException(
                                "exam paper", question.examPaperId()));
        UUID topicNodeId = question.primaryTopicNodeId();
        if (topicNodeId == null) {
            throw new NotFoundException("question topic anchor", questionId);
        }
        Subject subject = paper != null
                ? subjects.findById(paper.subjectId())
                        .orElseThrow(() -> new NotFoundException("subject", paper.subjectId()))
                : subjects.findAllByOrderByCode().stream()
                        .filter(s -> s.knowledgeNodeId() != null
                                && graph.subtreeIds(s.knowledgeNodeId()).contains(topicNodeId))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException(
                                "subject for question", questionId));

        UUID rootId = subject.knowledgeNodeId();
        if (rootId == null) {
            throw new NotFoundException("question topic anchor", questionId);
        }

        Map<UUID, NodeView> byId = new HashMap<>();
        collect(graph.tree(rootId), byId);
        NodeView topic = byId.get(topicNodeId);
        if (topic == null) {
            throw new NotFoundException("curriculum topic in this subject", topicNodeId);
        }

        QuestionVersion currentVersion = questionVersions
                .findByQuestionIdOrderByVersionDesc(questionId).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", questionId));
        if (currentVersion.validationState() != QuestionVersion.ValidationState.VALIDATED) {
            throw new NotFoundException("validated question", questionId);
        }

        KnowledgeNode topicNode = nodes.findById(topicNodeId)
                .orElseThrow(() -> new NotFoundException("curriculum topic", topicNodeId));
        if (topicNode.validationStatus() != KnowledgeNode.ValidationStatus.VALIDATED) {
            // curriculum validation gate on the spec anchor (contract §1.2)
            throw new NotFoundException("validated curriculum topic", topicNodeId);
        }

        boolean attempted = attempts.existsByLearnerIdAndQuestionId(learnerId, questionId);
        CurriculumVersion version = subject.curriculumVersion();
        return new ResourceContext(
                ResourceContext.Kind.PAST_PAPER_QUESTION,
                questionId,
                topicNodeId,
                rootId,
                subject.code(),
                topicNode.code(),
                topicNode.title(),
                new ResourceContext.CurriculumVersionInfo(
                        version.code(), version.board(), version.qualification(),
                        version.status().name()),
                currentVersion.validationState().name(),
                learnerId,
                Instant.now(),
                currentVersion.stem(),
                currentVersion.commandWord() != null ? currentVersion.commandWord()
                        : question.commandWord(),
                currentVersion.marks(),
                paper != null ? paper.paperCode() : null,
                attempted);
    }

    private void collect(NodeView node, Map<UUID, NodeView> byId) {
        byId.put(node.id(), node);
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                collect(child, byId);
            }
        }
    }
}
