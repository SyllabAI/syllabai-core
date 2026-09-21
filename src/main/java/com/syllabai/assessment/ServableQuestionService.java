package com.syllabai.assessment;

import com.syllabai.assessment.dto.QuestionTopicTaxonomyView;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.sme.SmeQuestionSpecPointRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner-facing servable-question read model (Master Spec §7/§22): the ONE
 * owner of the boundary rule "active + (MCQ) or (STRUCTURED with a VALIDATED
 * current version) — ingested/unvalidated content never serves". Extracted from
 * {@code QuestionController} so read models outside the assessment module
 * (T-033 next-best-action counting, retry targeting) reuse the exact same rule
 * instead of mirroring it and drifting.
 *
 * <p>V20 paper-level integrity gate: questions under a REJECTED or FLAGGED exam
 * paper never serve, even when their own versions are VALIDATED — a rejected or
 * flagged paper signals a systematic defect (wrong source, mis-placement, mass
 * extraction failure) that per-version validation cannot express. Paper-less
 * questions (SEED_DEMO orphans) are unaffected.</p>
 */
@Service
@Transactional(readOnly = true)
public class ServableQuestionService {

    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final ExamPaperRepository examPapers;
    private final SmeQuestionSpecPointRepository specPoints;
    private final QuestionTopicRepository topicMappings;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final KnowledgeEdgeRepository knowledgeEdges;
    private final KnowledgeGraphService knowledgeGraph;
    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    public ServableQuestionService(QuestionRepository questions,
                                   QuestionVersionRepository questionVersions,
                                   ExamPaperRepository examPapers,
                                   SmeQuestionSpecPointRepository specPoints,
                                   QuestionTopicRepository topicMappings,
                                   KnowledgeNodeRepository knowledgeNodes,
                                   KnowledgeEdgeRepository knowledgeEdges,
                                   KnowledgeGraphService knowledgeGraph) {
        this.questions = questions;
        this.questionVersions = questionVersions;
        this.examPapers = examPapers;
        this.specPoints = specPoints;
        this.topicMappings = topicMappings;
        this.knowledgeNodes = knowledgeNodes;
        this.knowledgeEdges = knowledgeEdges;
        this.knowledgeGraph = knowledgeGraph;
    }

    /** servable questions mapped to a topic (primary or question_topics), difficulty-ordered */
    public List<StudentQuestionView> activeByTopic(UUID topicNodeId) {
        return projectAll(filterBlockedPapers(questions.findActiveByTopic(topicNodeId)));
    }

    /** all servable questions, difficulty-ordered */
    public List<StudentQuestionView> allActive() {
        return projectAll(filterBlockedPapers(questions.findAllActive()));
    }

    /**
     * Curriculum codes per question id (ADR-026): PRIMARY mappings first, then
     * SECONDARY, each code-ordered — one batched query, empty lists for
     * questions without mappings (e.g. the seed MCQs).
     */
    private Map<UUID, List<String>> specPointCodes(Collection<UUID> questionIds) {
        Map<UUID, List<String>> byQuestion = new HashMap<>();
        if (questionIds.isEmpty()) {
            return byQuestion;
        }
        Map<UUID, List<SmeQuestionSpecPointRepository.CodeProjection>> grouped = specPoints
                .findCodesByQuestionIdsIn(questionIds).stream()
                .collect(Collectors.groupingBy(SmeQuestionSpecPointRepository.CodeProjection::getQuestionId));
        grouped.forEach((questionId, rows) -> {
            List<String> codes = new ArrayList<>(rows.stream()
                    .sorted(Comparator
                            .comparing((SmeQuestionSpecPointRepository.CodeProjection r) ->
                                    "PRIMARY".equals(r.getRole()) ? 0 : 1)
                            .thenComparing(SmeQuestionSpecPointRepository.CodeProjection::getCode))
                    .map(SmeQuestionSpecPointRepository.CodeProjection::getCode)
                    .toList());
            byQuestion.put(questionId, codes);
        });
        return byQuestion;
    }

    /**
     * All servable questions whose topic mapping falls inside the given node
     * set (a subject's PART_OF subtree) — the subject-scoped practice surface
     * (pilot-readiness session-56: the unscoped list served another subject's
     * questions under the 4CH1 surface, landing evidence the learner's
     * subject-scoped panels could not see). Same servability boundary, same
     * difficulty ordering, only the scope narrows.
     */
    public List<StudentQuestionView> activeWithin(java.util.Collection<UUID> nodeIds) {
        return projectAll(filterBlockedPapers(questions.findActiveWithin(nodeIds)));
    }

    /** a single servable question, or empty when missing/unservable (never throws) */
    public Optional<StudentQuestionView> findById(UUID id) {
        return questions.findWithOptions(id)
                .filter(q -> !paperBlocksServing(Set.of(), q.examPaperId()))
                .map(q -> project(q, currentVersion(q.id())))
                .filter(Objects::nonNull)
                .map(v -> v.withSpecPointCodes(
                        specPointCodes(List.of(v.id())).getOrDefault(v.id(), List.of())));
    }

    /** whether a question may still be served to learners (e.g. before recommending a retry) */
    public boolean isServable(UUID questionId) {
        return findById(questionId).isPresent();
    }

    /** how many servable questions exist on a topic (0 ⇒ honest empty state upstream) */
    public int countServableByTopic(UUID topicNodeId) {
        return activeByTopic(topicNodeId).size();
    }

    /**
     * The servable-question taxonomy (session-112): sections → topics with the
     * question counts the exam-questions browser sidebar and the practice topic
     * picker render. Computed under the SAME servability boundary as every list
     * path (this service is the one owner of that rule — the taxonomy cannot
     * drift from what {@code GET /api/v1/questions?topicNodeId=} actually serves).
     *
     * <p>A question counts under a topic when the topic list would serve it
     * there: PRIMARY mapping or any secondary {@code question_topics} mapping,
     * deduped per question — so the sidebar count is exactly the length of the
     * list a click loads. {@code rootId}, when given, scopes the taxonomy to a
     * subject's PART_OF subtree (unknown roots 404 exactly like the question
     * list); without it the taxonomy spans every subject with servable
     * questions.</p>
     *
     * <p>Grouping rides the knowledge graph: each counted topic node is grouped
     * under its PART_OF parent (the section — a UNIT node on 4CH1). Structural
     * duplicate PART_OF edges exist (the seed wrote several); a topic with
     * several distinct parents is grouped under the deterministically-lowest
     * parent id so the response is stable. Topics with no PART_OF parent, or
     * mapped to nodes missing from the graph, are not browsable and are skipped
     * — the taxonomy is the shape of what can be practised, not the syllabus.</p>
     */
    public QuestionTopicTaxonomyView taxonomy(UUID rootId) {
        // subject scope first: unknown root 404s here, before any question work
        Set<UUID> scope = rootId == null ? null
                : new HashSet<>(knowledgeGraph.subtreeIds(rootId));

        List<Question> candidates = filterBlockedPapers(questions.findAllActive());
        Map<UUID, QuestionVersion> currentByQuestion = currentVersions(candidates);

        List<Question> servableQuestions = candidates.stream()
                .filter(q -> servable.isSatisfiedBy(q, currentByQuestion.get(q.id())))
                .toList();

        // topic reachability per question: primary mapping first, then every
        // secondary question_topics mapping, deduped per (question, node)
        Map<UUID, Set<UUID>> topicsByQuestion = new HashMap<>();
        for (Question q : servableQuestions) {
            if (q.primaryTopicNodeId() != null) {
                topicsByQuestion.computeIfAbsent(q.id(), k -> new LinkedHashSet<>())
                        .add(q.primaryTopicNodeId());
            }
        }
        if (!servableQuestions.isEmpty()) {
            for (QuestionTopic mapping : topicMappings.findByQuestionIdIn(
                    servableQuestions.stream().map(Question::id).toList())) {
                topicsByQuestion.computeIfAbsent(mapping.questionId(), k -> new LinkedHashSet<>())
                        .add(mapping.nodeId());
            }
        }

        // per-topic census, scope-filtered — int[]{total, mcq, structured}
        Map<UUID, int[]> countsByTopic = new HashMap<>();
        for (Question q : servableQuestions) {
            for (UUID nodeId : topicsByQuestion.getOrDefault(q.id(), Set.of())) {
                if (scope != null && !scope.contains(nodeId)) {
                    continue;
                }
                int[] counts = countsByTopic.computeIfAbsent(nodeId, k -> new int[3]);
                counts[0]++;
                if (q.type() == Question.Type.MCQ_SINGLE) {
                    counts[1]++;
                } else if (q.type() == Question.Type.STRUCTURED) {
                    counts[2]++;
                }
            }
        }
        if (countsByTopic.isEmpty()) {
            return new QuestionTopicTaxonomyView(List.of());
        }

        // node metadata for the counted topics…
        Map<UUID, KnowledgeNode> nodeById = new HashMap<>();
        for (KnowledgeNode node : knowledgeNodes.findAllById(countsByTopic.keySet())) {
            nodeById.put(node.id(), node);
        }
        // …and their PART_OF parents (deduped against duplicate structural edges)
        Map<UUID, UUID> parentByTopic = new HashMap<>();
        for (KnowledgeEdge edge : knowledgeEdges.findPartOfEdgesFrom(countsByTopic.keySet())) {
            if (edge.relationType() != com.syllabai.knowledge.RelationType.PART_OF) {
                continue; // the query filters this; the guard documents the contract
            }
            parentByTopic.merge(edge.sourceId(), edge.targetId(),
                    (a, b) -> a.compareTo(b) < 0 ? a : b);
        }
        Map<UUID, KnowledgeNode> parentById = new HashMap<>();
        for (KnowledgeNode node : knowledgeNodes.findAllById(parentByTopic.values())) {
            parentById.put(node.id(), node);
        }

        // group topics under their section, both code-ordered (deterministic)
        Map<UUID, List<QuestionTopicTaxonomyView.Topic>> topicsBySection = new LinkedHashMap<>();
        for (Map.Entry<UUID, int[]> entry : countsByTopic.entrySet()) {
            KnowledgeNode topic = nodeById.get(entry.getKey());
            if (topic == null) {
                continue; // mapping to a node outside the graph cannot be browsed
            }
            KnowledgeNode section = parentById.get(parentByTopic.get(entry.getKey()));
            if (section == null) {
                continue; // no PART_OF parent: not browsable from a section sidebar
            }
            int[] counts = entry.getValue();
            topicsBySection.computeIfAbsent(section.id(), k -> new ArrayList<>())
                    .add(new QuestionTopicTaxonomyView.Topic(topic.id(), topic.code(),
                            topic.title(), counts[0], counts[1], counts[2]));
        }
        List<QuestionTopicTaxonomyView.Section> sections = topicsBySection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(id -> parentById.get(id).code())))
                .map(e -> {
                    KnowledgeNode section = parentById.get(e.getKey());
                    return new QuestionTopicTaxonomyView.Section(section.id(), section.code(),
                            section.title(),
                            e.getValue().stream()
                                    .sorted(Comparator.comparing(QuestionTopicTaxonomyView.Topic::code))
                                    .toList());
                })
                .toList();
        return new QuestionTopicTaxonomyView(sections);
    }

    /**
     * Current (highest-version) version per structured question, one batched
     * query — the fetch strategy every list-shaped path already uses.
     */
    private Map<UUID, QuestionVersion> currentVersions(Collection<Question> candidates) {
        List<UUID> structuredIds = candidates.stream()
                .filter(q -> q.type() == Question.Type.STRUCTURED)
                .map(Question::id)
                .toList();
        Map<UUID, QuestionVersion> currentByQuestion = new HashMap<>();
        if (!structuredIds.isEmpty()) {
            questionVersions.findWithPartsByQuestionIdsIn(structuredIds).stream()
                    .collect(Collectors.groupingBy(QuestionVersion::questionId))
                    .forEach((questionId, versions) -> versions.stream()
                            .max(Comparator.comparingInt(QuestionVersion::version))
                            .ifPresent(current -> currentByQuestion.put(questionId, current)));
        }
        return currentByQuestion;
    }

    /** V20: drop questions whose paper is REJECTED/FLAGGED (paper-level integrity gate) */
    private List<Question> filterBlockedPapers(List<Question> candidates) {
        Set<UUID> blocked = new HashSet<>(examPapers.findIdsBlockingServing());
        return candidates.stream()
                .filter(q -> !blocked.contains(q.examPaperId()))
                .toList();
    }

    /** V20: single-question fast path — consults the DB only when a paper exists */
    private boolean paperBlocksServing(Set<UUID> ignoredCache, UUID examPaperId) {
        if (examPaperId == null) {
            return false; // paper-less (SEED_DEMO orphans): per-question rule only
        }
        return examPapers.findIdsBlockingServing().contains(examPaperId);
    }

    /**
     * Batched projection for list-shaped reads: fetch ALL structured versions
     * (parts included) in one query, pick each question's current version, then
     * apply the same spec as the single-question path. Replaces the per-question
     * versions lookup that made the unscoped surface a ~1,800-query N+1.
     */
    private List<StudentQuestionView> projectAll(List<Question> candidates) {
        Map<UUID, QuestionVersion> currentByQuestion = currentVersions(candidates);
        Map<UUID, List<String>> codes = specPointCodes(
                candidates.stream().map(Question::id).toList());
        return candidates.stream()
                .map(q -> project(q, currentByQuestion.get(q.id())))
                .filter(Objects::nonNull)
                .map(v -> v.withSpecPointCodes(codes.getOrDefault(v.id(), List.of())))
                .toList();
    }

    /** latest version of one question (single-question paths) */
    private QuestionVersion currentVersion(UUID questionId) {
        return questionVersions.findByQuestionIdOrderByVersionDesc(questionId).stream()
                .findFirst()
                .orElse(null);
    }

    /** null when the spec rejects the question (unvalidated content never serves) */
    private StudentQuestionView project(Question question, QuestionVersion currentVersion) {
        if (question.type() != Question.Type.STRUCTURED) {
            return servable.isSatisfiedBy(question, null)
                    ? StudentQuestionView.from(question) : null;
        }
        return currentVersion == null
                ? null
                : servable.isSatisfiedBy(question, currentVersion)
                        ? StudentQuestionView.structured(question, currentVersion)
                        : null;
    }
}
