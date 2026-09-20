package com.syllabai.teacher;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecord;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher content-validation workflow (Master Spec §7): ingested content is
 * SUGGESTED and never serves until validated here. Reviewers approve/reject/flag
 * papers, question versions and mark schemes, and author the deterministic
 * acceptance criteria the Smart Mark pipeline relies on — the pipeline never
 * invents them.
 *
 * <p>V20 adds the FLAGGED state (flag from SUGGESTED/VALIDATED, unflag back to
 * SUGGESTED — re-validation required), the batch validate-all action for
 * high-throughput review (fail-closed against unreconciled imports), and the
 * quality-enriched review queue (strongest candidates first).</p>
 */
@Service
public class ContentReviewService {

    private static final Logger log = LoggerFactory.getLogger(ContentReviewService.class);

    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final MarkPointRepository markPoints;
    private final SubjectRepository subjects;
    private final GlmOcrBridgeRecordRepository bridgeRecords;
    private final QuestionRepository questions;
    private final QuestionTopicRepository questionTopics;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final ContentAuditRecorder audit;
    private final ContentReviewAuditRepository auditRepository;

    /** null-safe state name for audit rows (mocked entities in tests carry nulls) */
    private static String stateName(Enum<?> state) {
        return state == null ? null : state.name();
    }

    public ContentReviewService(ExamPaperRepository examPapers,
                                QuestionVersionRepository questionVersions,
                                MarkSchemeRepository markSchemes,
                                MarkPointRepository markPoints,
                                SubjectRepository subjects,
                                GlmOcrBridgeRecordRepository bridgeRecords,
                                QuestionRepository questions,
                                QuestionTopicRepository questionTopics,
                                KnowledgeNodeRepository knowledgeNodes,
                                ContentAuditRecorder audit,
                                ContentReviewAuditRepository auditRepository) {
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
        this.subjects = subjects;
        this.bridgeRecords = bridgeRecords;
        this.questions = questions;
        this.questionTopics = questionTopics;
        this.knowledgeNodes = knowledgeNodes;
        this.audit = audit;
        this.auditRepository = auditRepository;
    }

    /**
     * §7 placement: the ingestion pipeline never guesses curriculum placement —
     * imported papers wait in a neutral placeholder subject until a reviewer
     * places them into the real one. Placement is a factual association update
     * ONLY: validation states and the serving boundary are untouched, and it is
     * idempotent. AUDIT-logged because it changes what learners will see.
     */
    @Transactional
    public ExamPaper placePaper(UUID paperId, UUID subjectId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        Subject subject = subjects.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("subject", subjectId));
        if (subjectId.equals(paper.subjectId())) {
            return paper;
        }
        paper.assignSubject(subjectId);
        audit.record("PLACE", "exam_paper", paperId, null, null,
                "subject " + subjectId + " (" + subject.code() + ": " + subject.name() + ")");
        log.warn("AUDIT: exam paper {} ({} {}) placed into subject {} ({}: {}) "
                        + "during content review",
                paperId, paper.paperCode(), paper.sessionLabel(), subjectId,
                subject.code(), subject.name());
        return paper;
    }

    @Transactional
    public ExamPaper validatePaper(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        long unvalidated = versions.stream()
                .filter(v -> v.validationState() != QuestionVersion.ValidationState.VALIDATED)
                .count();
        if (unvalidated > 0) {
            throw new ConflictException("paper has " + unvalidated
                    + " unvalidated question version(s) — validate versions first");
        }
        assertMarkingContractComplete(paperId, versions, false);
        String from = stateName(paper.validationState());
        paper.validate();
        audit.record("VALIDATE", "exam_paper", paperId, from,
                stateName(paper.validationState()),
                paper.paperCode() + " " + paper.sessionLabel());
        log.info("exam paper {} validated by teacher", paperId);
        return paper;
    }

    /**
     * §7 marking-contract completeness: a question version with NO mark scheme
     * has no deterministic marking contract — attempts against it can be
     * submitted but can never be marked (PENDING forever). A paper must not
     * go VALIDATED while any version lacks its scheme, unless the reviewer
     * explicitly forces it (same override shape as the REVIEW_REQUIRED
     * bridge guard). Found live by the V20 battery: a June-2014 paper had six
     * scheme-less versions and batch validation published it silently.
     */
    private void assertMarkingContractComplete(UUID paperId,
                                               List<QuestionVersion> versions,
                                               boolean force) {
        if (force) {
            return;
        }
        long schemeless = versions.stream()
                .filter(v -> markSchemes
                        .findFirstByQuestionVersionIdOrderByCreatedAtDesc(v.id())
                        .isEmpty())
                .count();
        if (schemeless > 0) {
            throw new ConflictException("paper has " + schemeless
                    + " question version(s) without any mark scheme — the deterministic"
                    + " marking contract is incomplete; author the schemes first or pass"
                    + " force=true to validate anyway");
        }
    }

    @Transactional
    public ExamPaper rejectPaper(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        String from = stateName(paper.validationState());
        paper.reject();
        audit.record("REJECT", "exam_paper", paperId, from,
                stateName(paper.validationState()),
                paper.paperCode() + " " + paper.sessionLabel());
        return paper;
    }

    @Transactional
    public QuestionVersion validateQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        String from = stateName(version.validationState());
        version.validate();
        audit.record("VALIDATE", "question_version", versionId, from,
                stateName(version.validationState()), "");
        return version;
    }

    @Transactional
    public QuestionVersion rejectQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        String from = stateName(version.validationState());
        version.reject();
        audit.record("REJECT", "question_version", versionId, from,
                stateName(version.validationState()), "");
        return version;
    }

    /**
     * Validate a mark scheme, optionally authoring/reviewing acceptance criteria
     * per mark point in the same transaction (criteria are the deterministic
     * contract the Smart Mark prompt consumes).
     */
    @Transactional
    public MarkScheme validateMarkScheme(UUID schemeId,
                                         List<PointCriteria> criteriaUpdates) {
        return validateMarkScheme(schemeId, criteriaUpdates, null);
    }

    /**
     * V34 (gap G-3): {@code generalGuidance} authors the scheme-level board
     * instructions ("accept ecf", "ignore significant-figure penalties") that
     * have no per-point home; null/absent leaves any existing value untouched.
     */
    public MarkScheme validateMarkScheme(UUID schemeId,
                                         List<PointCriteria> criteriaUpdates,
                                         String generalGuidance) {
        MarkScheme scheme = markSchemes.findWithPoints(schemeId)
                .orElseThrow(() -> new NotFoundException("mark scheme", schemeId));
        if (criteriaUpdates != null) {
            for (PointCriteria update : criteriaUpdates) {
                MarkPoint point = scheme.points().stream()
                        .filter(p -> p.id().equals(update.markPointId()))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("mark point in scheme",
                                update.markPointId()));
                point.setAcceptanceCriteria(update.acceptanceCriteria());
            }
        }
        if (generalGuidance != null) {
            scheme.setGeneralGuidance(generalGuidance.isBlank() ? null : generalGuidance.strip());
        }
        String from = stateName(scheme.validationState());
        scheme.validate();
        audit.record("VALIDATE", "mark_scheme", schemeId, from,
                stateName(scheme.validationState()),
                (criteriaUpdates == null ? 0 : criteriaUpdates.size()) + " criteria updates"
                        + (generalGuidance == null ? "" : " + general guidance"));
        log.info("mark scheme {} validated ({} criteria updates{})",
                schemeId, criteriaUpdates == null ? 0 : criteriaUpdates.size(),
                generalGuidance == null ? "" : " + general guidance");
        return scheme;
    }

    @Transactional
    public MarkScheme rejectMarkScheme(UUID schemeId) {
        MarkScheme scheme = markSchemes.findById(schemeId)
                .orElseThrow(() -> new NotFoundException("mark scheme", schemeId));
        String from = stateName(scheme.validationState());
        scheme.reject();
        audit.record("REJECT", "mark_scheme", schemeId, from,
                stateName(scheme.validationState()), "");
        return scheme;
    }

    // ── V20: flag / unflag (all three levels) ────────────────────────────────

    @Transactional
    public ExamPaper flagPaper(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        String from = stateName(paper.validationState());
        paper.flag();
        audit.record("FLAG", "exam_paper", paperId, from,
                stateName(paper.validationState()), "serving blocked");
        log.warn("AUDIT: exam paper {} flagged — serving of everything under it is now blocked",
                paperId);
        return paper;
    }

    @Transactional
    public ExamPaper unflagPaper(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        String from = stateName(paper.validationState());
        paper.unflag();
        audit.record("UNFLAG", "exam_paper", paperId, from,
                stateName(paper.validationState()), "re-validation required");
        log.info("AUDIT: exam paper {} unflagged — back to SUGGESTED, re-validation required",
                paperId);
        return paper;
    }

    @Transactional
    public QuestionVersion flagQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        String from = stateName(version.validationState());
        version.flag();
        audit.record("FLAG", "question_version", versionId, from,
                stateName(version.validationState()), "serving blocked");
        return version;
    }

    @Transactional
    public QuestionVersion unflagQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        String from = stateName(version.validationState());
        version.unflag();
        audit.record("UNFLAG", "question_version", versionId, from,
                stateName(version.validationState()), "re-validation required");
        return version;
    }

    @Transactional
    public MarkScheme flagMarkScheme(UUID schemeId) {
        MarkScheme scheme = markSchemes.findById(schemeId)
                .orElseThrow(() -> new NotFoundException("mark scheme", schemeId));
        String from = stateName(scheme.validationState());
        scheme.flag();
        audit.record("FLAG", "mark_scheme", schemeId, from,
                stateName(scheme.validationState()), "serving blocked");
        return scheme;
    }

    @Transactional
    public MarkScheme unflagMarkScheme(UUID schemeId) {
        MarkScheme scheme = markSchemes.findById(schemeId)
                .orElseThrow(() -> new NotFoundException("mark scheme", schemeId));
        String from = stateName(scheme.validationState());
        scheme.unflag();
        audit.record("UNFLAG", "mark_scheme", schemeId, from,
                stateName(scheme.validationState()), "re-validation required");
        return scheme;
    }

    /**
     * V20 batch action (high-throughput review): validate every SUGGESTED
     * question version and mark scheme of the paper in ONE transaction, then the
     * paper itself — the same §7 semantics as the per-item buttons, applied to
     * the whole paper at once. Fail-closed guards:
     * <ul>
     *   <li>the paper's glm-ocr bridge reconciliation must be OK (a
     *       REVIEW_REQUIRED import must be reviewed item-by-item) unless
     *       {@code force} is explicitly set by the reviewer;</li>
     *   <li>no version may be REJECTED or FLAGGED — those are reviewer decisions
     *       the batch must never silently overwrite (409 naming the blocker).</li>
     * </ul>
     */
    @Transactional
    public BatchResult validateAllForPaper(UUID paperId, boolean force) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        if (paper.validationState() == ExamPaper.ValidationState.REJECTED) {
            throw new ConflictException("paper is REJECTED — validation is not possible");
        }
        if (paper.validationState() == ExamPaper.ValidationState.FLAGGED) {
            throw new ConflictException("paper is FLAGGED — unflag it before validating");
        }

        GlmOcrBridgeRecord bridge = bridgeRecords.findByPaperId(paperId).orElse(null);
        boolean reviewRequired = bridge != null
                && "REVIEW_REQUIRED".equals(bridge.reconciliationStatus());
        if (reviewRequired && !force) {
            throw new ConflictException("paper import has REVIEW_REQUIRED reconciliation"
                    + " — review its findings item-by-item, or pass force=true to validate anyway");
        }

        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        long blocked = versions.stream()
                .filter(v -> v.validationState() == QuestionVersion.ValidationState.REJECTED
                        || v.validationState() == QuestionVersion.ValidationState.FLAGGED)
                .count();
        if (blocked > 0) {
            throw new ConflictException("paper has " + blocked
                    + " REJECTED/FLAGGED question version(s) — resolve them before batch validation");
        }

        // fail fast on the marking contract BEFORE mutating anything
        assertMarkingContractComplete(paperId, versions, force);

        int versionsValidated = 0;
        int schemesValidated = 0;
        for (QuestionVersion version : versions) {
            if (version.validationState() == QuestionVersion.ValidationState.SUGGESTED) {
                version.validate();
                versionsValidated++;
                audit.record("VALIDATE", "question_version", version.id(), "SUGGESTED",
                        "VALIDATED", "batch validate-all");
            }
            MarkScheme scheme = markSchemes
                    .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                    .orElse(null);
            if (scheme != null
                    && scheme.validationState() == MarkScheme.ValidationState.SUGGESTED) {
                scheme.validate();
                schemesValidated++;
                audit.record("VALIDATE", "mark_scheme", scheme.id(), "SUGGESTED",
                        "VALIDATED", "batch validate-all");
            }
        }

        // the paper flip re-uses the exact per-item precondition (all versions VALIDATED)
        long unvalidated = versions.stream()
                .filter(v -> v.validationState() != QuestionVersion.ValidationState.VALIDATED)
                .count();
        if (unvalidated > 0) {
            throw new ConflictException("paper has " + unvalidated
                    + " unvalidated question version(s) — validate versions first");
        }
        String paperFrom = stateName(paper.validationState());
        paper.validate();
        audit.record("VALIDATE_ALL", "exam_paper", paperId, paperFrom,
                stateName(paper.validationState()), versionsValidated + " versions + "
                        + schemesValidated + " schemes, force=" + force);
        log.info("AUDIT: exam paper {} batch-validated ({} versions, {} schemes, force={})",
                paperId, versionsValidated, schemesValidated, force);
        return new BatchResult(paper.id(), paper.validationState().name(),
                versions.size(), versionsValidated, schemesValidated);
    }

    /**
     * V20 quality-enriched review queue: the SUGGESTED papers with per-paper
     * progress, bridge reconciliation status, parser-finding count and mean
     * extraction confidence — sorted strongest-candidates-first (reconciled OK,
     * then confidence desc, then fewer findings, then newest) so a reviewer's
     * limited attention lands on the most trustworthy imports first. Ambiguous
     * material stays in the queue; nothing is promoted by ordering.
     */
    @Transactional(readOnly = true)
    public EnrichedReviewQueueView enrichedReviewQueue() {
        List<EnrichedPaperSummary> enriched = baseEnrichment();
        return new EnrichedReviewQueueView(enriched, suggestedVersionCount(enriched),
                suggestedSchemeCount(enriched));
    }

    /**
     * Sprint 2 §7 review-queue v3: the v2 quality enrichment PLUS the
     * reviewability and value signals a reviewer actually triages by —
     * mark-scheme linkage completeness (can the answer key be checked against
     * source?), curriculum mapping coverage (how much of the paper is already
     * placed on the real syllabus?) and novel coverage (how many topics would
     * this paper let the pilot practise that validated content cannot yet?).
     *
     * <p>Ordering is deterministic and documented, never a fabricated
     * confidence: reconciled-OK papers first (lowest reconciliation risk),
     * then scheme-linkage ratio, mapping ratio, novel-topic count, mean
     * extraction confidence, fewer findings, newest, then id. Each paper
     * carries its human-legible rank REASONS — only signals that actually
     * hold are stated; absent signals stay null and are never zero-filled.
     * Ordering changes which paper a reviewer SEES first; it never promotes
     * anything or weakens any gate.</p>
     */
    @Transactional(readOnly = true)
    public EnrichedReviewQueueViewV3 enrichedReviewQueueV3() {
        List<EnrichedPaperSummary> base = baseEnrichment();

        // §7 signals — one batched query each, grouped per paper in memory
        Map<UUID, Long> questionsWithScheme = new HashMap<>();
        for (Object[] row : markSchemes.countQuestionsWithSchemesByPaper()) {
            questionsWithScheme.put((UUID) row[0], (Long) row[1]);
        }
        Map<UUID, long[]> questionCensus = new HashMap<>();   // [total, mapped]
        for (Object[] row : questions.countAndMappedByPaper()) {
            questionCensus.put((UUID) row[0],
                    new long[]{(Long) row[1], (Long) row[2]});
        }
        Map<UUID, Set<UUID>> mappedTopicsByPaper = new HashMap<>();
        for (Object[] row : questionTopics.findMappingsByPaper()) {
            mappedTopicsByPaper.computeIfAbsent((UUID) row[0], k -> new HashSet<>())
                    .add((UUID) row[1]);
        }

        // the topics the pilot can already practise: primary topics AND mapped
        // topics of every VALIDATED paper's questions (both are curriculum
        // node ids once a question serves — the §1 census proved that)
        Set<UUID> validatedIds = examPapers.findValidated().stream()
                .map(ExamPaper::id).collect(Collectors.toSet());
        Set<UUID> practicableTopics = new HashSet<>(
                questions.findDistinctPrimaryTopicsByPaperIds(validatedIds));
        for (Map.Entry<UUID, Set<UUID>> e : mappedTopicsByPaper.entrySet()) {
            if (validatedIds.contains(e.getKey())) {
                practicableTopics.addAll(e.getValue());
            }
        }

        List<EnrichedPaperSummaryV3> enriched = new ArrayList<>(base.size());
        for (EnrichedPaperSummary p : base) {
            long[] census = questionCensus.getOrDefault(p.id(), new long[]{0, 0});
            long withScheme = questionsWithScheme.getOrDefault(p.id(), 0L);
            Set<UUID> novel = new HashSet<>(
                    mappedTopicsByPaper.getOrDefault(p.id(), Set.of()));
            novel.removeAll(practicableTopics);

            enriched.add(EnrichedPaperSummaryV3.from(p, census[0], census[1],
                    withScheme, novel.size(), rankReasons(p, census[0], census[1],
                    withScheme, novel.size())));
        }

        enriched.sort(Comparator
                .comparing((EnrichedPaperSummaryV3 p) ->
                        "OK".equals(p.reconciliationStatus()) ? 0 : 1)
                .thenComparing(p -> schemeRatio(p), Comparator.reverseOrder())
                .thenComparing(p -> mappingRatio(p), Comparator.reverseOrder())
                .thenComparingLong(EnrichedPaperSummaryV3::novelTopicCount)
                .thenComparing(p -> p.avgExtractionConfidence() == null ? 0.0
                        : p.avgExtractionConfidence(), Comparator.reverseOrder())
                .thenComparingLong(EnrichedPaperSummaryV3::findingCount)
                .thenComparing(EnrichedPaperSummaryV3::createdAt,
                        Comparator.reverseOrder())
                .thenComparing(EnrichedPaperSummaryV3::id));

        return new EnrichedReviewQueueViewV3(enriched, suggestedVersionCount(base),
                suggestedSchemeCount(base), practicableTopics.size());
    }

    /** the v2 enrichment over the SUGGESTED papers (shared by v2 and v3) */
    private List<EnrichedPaperSummary> baseEnrichment() {
        List<ExamPaper> papers = examPapers.findSuggested();

        Map<UUID, Map<QuestionVersion.ValidationState, Long>> versionCounts =
                new HashMap<>();
        for (Object[] row : questionVersions.countByPaperAndState()) {
            UUID paperId = (UUID) row[0];
            @SuppressWarnings("unchecked")
            QuestionVersion.ValidationState state =
                    (QuestionVersion.ValidationState) row[1];
            versionCounts.computeIfAbsent(paperId, k -> new HashMap<>())
                    .put(state, (Long) row[2]);
        }
        Map<UUID, Map<MarkScheme.ValidationState, Long>> schemeCounts = new HashMap<>();
        for (Object[] row : markSchemes.countByPaperAndState()) {
            UUID paperId = (UUID) row[0];
            @SuppressWarnings("unchecked")
            MarkScheme.ValidationState state = (MarkScheme.ValidationState) row[1];
            schemeCounts.computeIfAbsent(paperId, k -> new HashMap<>())
                    .put(state, (Long) row[2]);
        }
        Map<UUID, Double> confidence = new HashMap<>();
        for (Object[] row : questionVersions.avgExtractionConfidenceByPaper()) {
            confidence.put((UUID) row[0], ((Number) row[1]).doubleValue());
        }

        List<EnrichedPaperSummary> enriched = new ArrayList<>();
        for (ExamPaper paper : papers) {
            Map<QuestionVersion.ValidationState, Long> vc =
                    versionCounts.getOrDefault(paper.id(), Map.of());
            Map<MarkScheme.ValidationState, Long> sc =
                    schemeCounts.getOrDefault(paper.id(), Map.of());
            GlmOcrBridgeRecord bridge = bridgeRecords.findByPaperId(paper.id()).orElse(null);
            long findingCount = 0;
            if (bridge != null && bridge.reviewFindings() != null) {
                findingCount = bridge.reviewFindings().split("\"source\"").length - 1;
            }
            enriched.add(new EnrichedPaperSummary(
                    paper.id(), paper.subjectId(), paper.title(), paper.paperCode(),
                    paper.sessionLabel(), paper.board(), paper.qualification(),
                    paper.validationState().name(),
                    vc.values().stream().mapToLong(Long::longValue).sum(),
                    vc.getOrDefault(QuestionVersion.ValidationState.VALIDATED, 0L),
                    vc.getOrDefault(QuestionVersion.ValidationState.REJECTED, 0L),
                    vc.getOrDefault(QuestionVersion.ValidationState.FLAGGED, 0L),
                    sc.getOrDefault(MarkScheme.ValidationState.SUGGESTED, 0L),
                    bridge == null ? null : bridge.reconciliationStatus(),
                    findingCount,
                    confidence.get(paper.id()),
                    paper.createdAt()));
        }

        enriched.sort(Comparator
                .comparing((EnrichedPaperSummary p) ->
                        "OK".equals(p.reconciliationStatus()) ? 0 : 1) // reconciled first
                .thenComparing(p -> p.avgExtractionConfidence() == null ? 0.0
                        : p.avgExtractionConfidence(), Comparator.reverseOrder())
                .thenComparingLong(EnrichedPaperSummary::findingCount)
                .thenComparing(EnrichedPaperSummary::createdAt,
                        Comparator.reverseOrder()));
        return enriched;
    }

    /** versions still awaiting a decision, from the enrichment already computed */
    private static int suggestedVersionCount(List<EnrichedPaperSummary> enriched) {
        return (int) enriched.stream()
                .mapToLong(p -> p.versionCount() - p.validatedVersions()
                        - p.rejectedVersions() - p.flaggedVersions())
                .sum();
    }

    /** schemes still awaiting a decision, from the enrichment already computed */
    private static int suggestedSchemeCount(List<EnrichedPaperSummary> enriched) {
        return (int) enriched.stream()
                .mapToLong(EnrichedPaperSummary::suggestedSchemes).sum();
    }

    /** scheme-linkage ratio for ordering (no questions → sorts last, display stays honest) */
    private static double schemeRatio(EnrichedPaperSummaryV3 p) {
        return p.totalQuestions() == 0 ? -1.0
                : (double) p.questionsWithScheme() / p.totalQuestions();
    }

    /** mapping ratio for ordering (no questions → sorts last, display stays honest) */
    private static double mappingRatio(EnrichedPaperSummaryV3 p) {
        return p.totalQuestions() == 0 ? -1.0
                : (double) p.mappedQuestions() / p.totalQuestions();
    }

    /**
     * The §7 rank reasons — only signals that actually hold, in the priority
     * order the queue sorts by. Never a fabricated number; absent confidence
     * is simply not stated.
     */
    private static List<String> rankReasons(EnrichedPaperSummary p,
                                            long totalQuestions, long mappedQuestions,
                                            long withScheme, int novelTopicCount) {
        List<String> reasons = new ArrayList<>();
        if ("OK".equals(p.reconciliationStatus())) {
            reasons.add("bridge reconciled OK");
        }
        if (totalQuestions > 0) {
            if (withScheme >= totalQuestions) {
                reasons.add("mark scheme linked for all "
                        + totalQuestions + " question(s)");
            } else if (withScheme > 0) {
                reasons.add("mark scheme linked for " + withScheme + "/"
                        + totalQuestions + " question(s)");
            } else {
                reasons.add("no mark scheme linked yet");
            }
            if (mappedQuestions >= totalQuestions) {
                reasons.add("all " + totalQuestions + " question(s) mapped to curriculum");
            } else if (mappedQuestions > 0) {
                reasons.add(mappedQuestions + "/" + totalQuestions
                        + " question(s) mapped to curriculum");
            }
        }
        if (novelTopicCount > 0) {
            reasons.add("brings " + novelTopicCount
                    + " topic(s) not yet practicable from validated content");
        }
        if (p.avgExtractionConfidence() != null) {
            reasons.add(String.format("mean extraction confidence %.2f",
                    p.avgExtractionConfidence()));
        }
        if (p.findingCount() == 0 && "OK".equals(p.reconciliationStatus())) {
            reasons.add("no parser findings");
        }
        return reasons;
    }

    /**
     * v3 paper summary — FLAT by design: every v2 signal plus the §7
     * reviewability/value signals and the rank reasons, so the JSON mirrors
     * the v2 shape plus additions (records serialize their components; a
     * nested base would hide the v2 fields).
     */
    public record EnrichedPaperSummaryV3(UUID id, UUID subjectId, String title,
                                         String paperCode, String sessionLabel,
                                         String board, String qualification,
                                         String validationState,
                                         long versionCount, long validatedVersions,
                                         long rejectedVersions, long flaggedVersions,
                                         long suggestedSchemes, String reconciliationStatus,
                                         long findingCount, Double avgExtractionConfidence,
                                         java.time.Instant createdAt,
                                         long totalQuestions, long mappedQuestions,
                                         long questionsWithScheme, int novelTopicCount,
                                         List<String> rankReasons) {

        /** project a v2 summary into the flat v3 shape plus its new signals */
        public static EnrichedPaperSummaryV3 from(EnrichedPaperSummary p,
                                                  long totalQuestions,
                                                  long mappedQuestions,
                                                  long withScheme,
                                                  int novelTopicCount,
                                                  List<String> reasons) {
            return new EnrichedPaperSummaryV3(
                    p.id(), p.subjectId(), p.title(), p.paperCode(), p.sessionLabel(),
                    p.board(), p.qualification(), p.validationState(), p.versionCount(),
                    p.validatedVersions(), p.rejectedVersions(), p.flaggedVersions(),
                    p.suggestedSchemes(), p.reconciliationStatus(), p.findingCount(),
                    p.avgExtractionConfidence(), p.createdAt(),
                    totalQuestions, mappedQuestions, withScheme, novelTopicCount, reasons);
        }
    }

    public record EnrichedReviewQueueViewV3(List<EnrichedPaperSummaryV3> papers,
                                            int suggestedVersions, int suggestedSchemes,
                                            int practicableTopicCount) {
    }

    /** result of a batch validation */
    public record BatchResult(UUID paperId, String paperState, int totalVersions,
                              int versionsValidated, int schemesValidated) {
    }

    // ── §10 topic mapping: ingestion anchors -> real curriculum topics ───────

    /**
     * Map a question to its real curriculum topic(s). Ingestion deliberately
     * parks every imported question on a per-paper "ingestion anchor" node (a
     * disconnected placeholder, NOT in any subject subtree) so the NOT NULL
     * primary-topic constraint holds without the pipeline guessing curriculum
     * placement — the same §7 principle as paper placement. Until a reviewer
     * maps the question here, subject-scoped practice cannot see it (the anchor
     * is outside every subject's PART_OF subtree, by design).
     *
     * <p>Replaces the question's topic rows atomically: primary becomes the
     * question's primary_topic_node_id AND a primary question_topics row;
     * secondaries (optional, deduplicated, max 5) become non-primary rows.
     * AUDIT-logged because it changes what learners will practice.</p>
     */
    @Transactional
    public TopicMappingResult mapQuestionTopics(UUID questionId, UUID primaryNodeId,
                                                List<UUID> secondaryNodeIds) {
        Question question = questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("question", questionId));
        KnowledgeNode primary = knowledgeNodes.findById(primaryNodeId)
                .orElseThrow(() -> new NotFoundException("curriculum topic", primaryNodeId));
        if (primary.code() != null && primary.code().startsWith("ING-")) {
            throw new ConflictException("primary topic " + primary.code()
                    + " is an ingestion anchor — pick a real curriculum topic");
        }

        LinkedHashSet<UUID> secondaries = new LinkedHashSet<>();
        if (secondaryNodeIds != null) {
            for (UUID id : secondaryNodeIds) {
                if (id.equals(primaryNodeId)) {
                    continue; // the primary row already covers it
                }
                KnowledgeNode node = knowledgeNodes.findById(id)
                        .orElseThrow(() -> new NotFoundException("curriculum topic", id));
                if (node.code() != null && node.code().startsWith("ING-")) {
                    throw new ConflictException("secondary topic " + node.code()
                            + " is an ingestion anchor — pick real curriculum topics");
                }
                secondaries.add(id);
                if (secondaries.size() >= 5) {
                    break; // multi-topic, not a keyword dump
                }
            }
        }

        question.assignPrimaryTopic(primaryNodeId);
        questionTopics.deleteByQuestionId(questionId);
        // Hibernate orders INSERTs before DELETEs within one flush: re-mapping a
        // question onto a node it already has would violate uq_question_topic
        // unless the delete reaches the DB first. flush() is the barrier.
        questionTopics.flush();
        questionTopics.save(new QuestionTopic(question, primaryNodeId, true));
        for (UUID secondary : secondaries) {
            questionTopics.save(new QuestionTopic(question, secondary, false));
        }
        audit.record("MAP_TOPICS", "question", questionId, null, null,
                "primary " + primary.code() + " + " + secondaries.size() + " secondary");
        log.info("AUDIT: question {} mapped to primary topic {} ({}) + {} secondary topic(s)",
                questionId, primary.code(), primaryNodeId, secondaries.size());
        return new TopicMappingResult(questionId, primaryNodeId, primary.code(),
                primary.title(), secondaries.size() + 1);
    }

    /** current mapping of a question (its topic rows, anchor state visible) */
    @Transactional(readOnly = true)
    public List<TopicRowView> questionTopicRows(UUID questionId) {
        Question question = questions.findById(questionId)
                .orElseThrow(() -> new NotFoundException("question", questionId));
        List<TopicRowView> rows = questionTopics.findByQuestionId(questionId).stream()
                .map(row -> {
                    KnowledgeNode node = knowledgeNodes.findById(row.nodeId()).orElse(null);
                    return new TopicRowView(row.nodeId(), row.primary(),
                            node == null ? null : node.code(),
                            node == null ? null : node.title());
                })
                .toList();
        // Ingested questions carry their ingestion anchor ONLY in
        // primary_topic_node_id — no question_topics row is written for it, so
        // the naive projection above returns an EMPTY list and the reviewer
        // sees "no mapping" where the truth is "mapped to anchor ING-…". A
        // reviewer cannot map a question off an anchor they cannot see (§10);
        // found live by the V20 production battery. Synthesize the anchor row.
        boolean hasPrimaryRow = rows.stream().anyMatch(TopicRowView::primary);
        if (!hasPrimaryRow) {
            UUID anchor = question.primaryTopicNodeId();
            if (anchor != null) {
                KnowledgeNode node = knowledgeNodes.findById(anchor).orElse(null);
                TopicRowView anchorRow = new TopicRowView(anchor, true,
                        node == null ? null : node.code(),
                        node == null ? null : node.title());
                List<TopicRowView> out = new ArrayList<>(rows.size() + 1);
                out.add(anchorRow);
                rows.stream().filter(r -> !r.primary()).forEach(out::add);
                return List.copyOf(out);
            }
        }
        return rows;
    }

    public record TopicMappingResult(UUID questionId, UUID primaryNodeId, String primaryCode,
                                     String primaryTitle, int topicCount) {
    }

    public record TopicRowView(UUID nodeId, boolean primary, String code, String title) {
    }

    /**
     * V22: the paper's full audit history — its own rows plus every row of its
     * question versions, mark schemes and questions. Read-only projection of
     * content_review_audit; the reviewer sees WHO decided WHAT and WHEN.
     */
    @Transactional(readOnly = true)
    public List<AuditRowView> paperAudit(UUID paperId) {
        examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        List<UUID> versionIds = questionVersions.findByPaperId(paperId).stream()
                .map(QuestionVersion::id).toList();
        List<UUID> schemeIds = markSchemes.findByPaperId(paperId).stream()
                .map(MarkScheme::id).toList();
        List<UUID> questionIds = questions.findAllByExamPaperIdOrderByDifficultyAsc(paperId)
                .stream().map(Question::id).toList();
        return auditRepository.findPaperAudit(paperId, versionIds, schemeIds, questionIds)
                .stream().map(AuditRowView::from).toList();
    }

    public record AuditRowView(String occurredAt, String actor, String action,
                               String targetType, UUID targetId,
                               String fromState, String toState, String detail) {
        static AuditRowView from(ContentReviewAudit row) {
            return new AuditRowView(
                    row.occurredAt() == null ? null : row.occurredAt().toString(),
                    row.actorLabel(), row.action(), row.targetType(), row.targetId(),
                    row.fromState(), row.toState(), row.detail());
        }
    }

    /** one SUGGESTED paper with the quality signals a reviewer triages by */
    public record EnrichedPaperSummary(UUID id, UUID subjectId, String title,
                                       String paperCode, String sessionLabel, String board,
                                       String qualification, String validationState,
                                       long versionCount, long validatedVersions,
                                       long rejectedVersions, long flaggedVersions,
                                       long suggestedSchemes, String reconciliationStatus,
                                       long findingCount, Double avgExtractionConfidence,
                                       java.time.Instant createdAt) {
    }

    public record EnrichedReviewQueueView(List<EnrichedPaperSummary> papers,
                                          int suggestedVersions, int suggestedSchemes) {
    }

    /**
     * @param markPointId        the point the criteria belong to
     * @param acceptanceCriteria deterministic matching criteria (may be empty)
     */
    public record PointCriteria(UUID markPointId, List<String> acceptanceCriteria) {
    }

    // ── teacher review read model (§7: reviewers must see WHAT they validate) ──

    /**
     * Full review view of one paper's question versions — unlike the learner
     * projections this INCLUDES the answer key (correct options, misconceptions,
     * mark points), because a reviewer cannot validate content they cannot see.
     * Still a read-only projection: no serving-boundary change, SUGGESTED
     * content remains un-servable for learners.
     */
    @Transactional(readOnly = true)
    public PaperReviewView paperReview(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        List<VersionReviewView> reviewViews = versions.stream()
                .map(this::toVersionReviewView)
                .toList();
        return new PaperReviewView(
                new PaperReviewView.PaperHeader(paper.id(), paper.subjectId(), paper.title(),
                        paper.paperCode(),
                        paper.sessionLabel(), paper.board(), paper.qualification(),
                        paper.validationState().name()),
                reviewViews);
    }

    private VersionReviewView toVersionReviewView(QuestionVersion version) {
        Question question = version.question();
        List<VersionReviewView.OptionReview> options = question.options().stream()
                .map(o -> new VersionReviewView.OptionReview(o.id(), o.label(), o.text(),
                        o.correct(), o.misconceptionNodeId()))
                .toList();
        List<VersionReviewView.PartReview> parts = version.parts().stream()
                .map(p -> new VersionReviewView.PartReview(p.id(), p.label(), p.prompt(),
                        p.commandWord(), p.marks()))
                .toList();
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                .orElse(null);
        List<VersionReviewView.PointReview> points = scheme == null ? List.of()
                : scheme.points().stream()
                        .map(mp -> new VersionReviewView.PointReview(mp.id(), mp.ref(),
                                mp.text(), mp.marks(),
                                mp.acceptanceCriteria() == null ? List.of()
                                        : mp.acceptanceCriteria()))
                        .toList();
        return new VersionReviewView(
                version.id(), question.id(), question.externalRef(), question.type().name(),
                version.stem() == null ? question.stem() : version.stem(),
                version.marks() > 0 ? version.marks() : question.marks(),
                version.version(), version.validationState().name(), version.commandWord(),
                scheme == null ? null : scheme.id(),
                scheme == null ? null : scheme.validationState().name(),
                points, options, parts,
                version.extractionConfidence(), version.extractionMethod(),
                version.sourceDocumentId());
    }

    /**
     * Teacher-facing review projection of a paper: header + every question
     * version with its full answer key and mark-scheme state.
     */
    public record PaperReviewView(PaperHeader paper, List<VersionReviewView> versions) {

        public record PaperHeader(UUID id, UUID subjectId, String title, String paperCode,
                                  String sessionLabel, String board, String qualification,
                                  String validationState) {
        }
    }

    public record VersionReviewView(
            UUID versionId, UUID questionId, String externalRef, String type,
            String stem, int marks, int version, String validationState, String commandWord,
            UUID schemeId, String schemeState,
            List<PointReview> points, List<OptionReview> options, List<PartReview> parts,
            Double extractionConfidence, String extractionMethod, String sourceDocumentId) {

        /** teacher-only: includes the correct flag and the misconception the distractor feeds */
        public record OptionReview(UUID id, String label, String text, boolean correct,
                                   UUID misconceptionNodeId) {
        }

        public record PartReview(UUID id, String label, String prompt, String commandWord,
                                 int marks) {
        }

        /** teacher-only: the deterministic marking contract per mark point */
        public record PointReview(UUID id, String ref, String text, int marks,
                                  List<String> acceptanceCriteria) {
        }
    }
}
