package com.syllabai.teacher.ingestion;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.shared.ConflictException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingests a syllabai-parser past-paper draft into the content bank (T-011 bridge).
 * Deterministic, whole-draft, single transaction: either the full paper lands or
 * nothing does.
 *
 * <p>Everything is created in SUGGESTED state. Topic mapping: v0 drafts carry no KG
 * references, so each paper gets one <em>ingestion anchor</em> TOPIC node (UNVALIDATED,
 * provenance-tracked) that its questions are tagged to; teachers remap topics during
 * review — the pipeline never guesses curriculum placement (Master Spec §7).</p>
 */
@Service
public class PastPaperIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PastPaperIngestionService.class);

    private final ExamPaperRepository examPapers;
    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final SubjectRepository subjects;
    private final CurriculumVersionRepository curriculumVersions;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final KnowledgeEdgeRepository knowledgeEdges;

    public PastPaperIngestionService(ExamPaperRepository examPapers,
                                     QuestionRepository questions,
                                     QuestionVersionRepository questionVersions,
                                     MarkSchemeRepository markSchemes,
                                     SubjectRepository subjects,
                                     CurriculumVersionRepository curriculumVersions,
                                     KnowledgeNodeRepository knowledgeNodes,
                                     KnowledgeEdgeRepository knowledgeEdges) {
        this.examPapers = examPapers;
        this.questions = questions;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.subjects = subjects;
        this.curriculumVersions = curriculumVersions;
        this.knowledgeNodes = knowledgeNodes;
        this.knowledgeEdges = knowledgeEdges;
    }

    @Transactional
    public IngestionSummary ingest(PastPaperDraftDto draft, UUID ingestedBy) {
        if (draft.paper() == null || draft.questions() == null || draft.questions().isEmpty()) {
            throw new ConflictException("draft has no paper metadata or no questions");
        }
        PastPaperDraftDto.PaperMeta meta = draft.paper();
        if (meta.paperCode() != null && meta.sessionLabel() != null
                && examPapers.findByPaperCodeAndSessionLabel(meta.paperCode(), meta.sessionLabel()).isPresent()) {
            throw new ConflictException("paper " + meta.paperCode() + " " + meta.sessionLabel()
                    + " already ingested");
        }
        // Identity gate (fail-closed): a paper without a printed/derived session label
        // has no reviewable identity — it would land as a nameless "null …" row that
        // teacher review cannot attribute to any exam session. The parser now recovers
        // identity from printed table-cell covers, and the pair CLI accepts
        // operator-supplied --session-label/--paper-code for covers the OCR lost;
        // if neither exists the draft is rejected instead of silently ingested.
        if (meta.sessionLabel() == null || meta.sessionLabel().isBlank()) {
            throw new ConflictException("paper identity incomplete: no session label "
                    + "(refusing a nameless paper — supply --session-label at parse time)");
        }

        Subject subject = resolveSubject(meta);
        UUID anchorTopic = createIngestionAnchor(meta, subject, ingestedBy);

        // defensive bounds for the VARCHAR columns (parser drafts are untrusted input)
        String paperCode = bound(meta.paperCode(), 30);
        String sessionLabel = bound(meta.sessionLabel(), 60);

        ExamPaper paper = examPapers.save(new ExamPaper(
                subject.id(),
                // title from printed identity only — null components are skipped, never
                // string-concatenated as the literal "null" (the old behaviour produced
                // titles like "null Summer 2013" whenever paperCode and unit were absent)
                bound(paperTitle(meta, paperCode, sessionLabel), 200),
                bound(nullSafe(meta.board(), "unknown-board"), 40),
                bound(nullSafe(meta.qualification(), "unknown"), 20),
                bound(meta.unit(), 60),
                sessionLabel,
                paperCode,
                bound(meta.questionPaperDocumentId(), 80),
                bound(meta.markSchemeDocumentId(), 80),
                ExamPaper.Provenance.PAST_PAPER,
                bound(draft.extractionMethod(), 120),
                ingestedBy));

        int questionCount = 0;
        int partCount = 0;
        Map<String, QuestionVersion> versionsByNumber = new HashMap<>();
        for (PastPaperDraftDto.QuestionDraft q : draft.questions()) {
            Question question = questions.save(new Question(
                    q.externalRef(),
                    Question.Type.STRUCTURED,
                    nullSafe(q.prompt(), ""),
                    Math.max(q.marks(), 1),
                    3,                                   // difficulty unknown until review
                    90 * Math.max(q.marks(), 1),          // 90s/mark heuristic, reviewable
                    q.commandWord(),
                    anchorTopic,
                    Question.Provenance.PAST_PAPER));
            question.attachToPaper(paper.id());
            QuestionVersion version = questionVersions.save(new QuestionVersion(
                    question, 1, nullSafe(q.prompt(), ""),
                    Math.max(q.marks(), 1), 3, 90 * Math.max(q.marks(), 1),
                    q.commandWord(),
                    QuestionVersion.ValidationState.SUGGESTED,
                    meta.questionPaperDocumentId(),
                    q.confidence(),
                    draft.extractionMethod()));
            versionsByNumber.put(q.questionNumber(), version);
            questionCount++;
            int order = 0;
            for (PastPaperDraftDto.PartDraft p : q.parts()) {
                version.addPart(new QuestionPart(version, p.label(),
                        nullSafe(p.prompt(), ""), p.commandWord(),
                        Math.max(p.marks(), 0), order++));
                partCount++;
            }
        }

        int pointCount = 0;
        if (draft.markScheme() != null && draft.markScheme().points() != null) {
            for (Map.Entry<String, QuestionVersion> e : versionsByNumber.entrySet()) {
                QuestionVersion version = e.getValue();
                List<PastPaperDraftDto.MarkPointDraft> mine =
                        pointsForQuestion(draft.markScheme().points(), e.getKey());
                if (mine.isEmpty()) {
                    continue;
                }
                MarkScheme scheme = markSchemes.save(new MarkScheme(
                        version, nullSafe(draft.markScheme().version(), "1"),
                        draft.markScheme().sourceDocumentId(),
                        draft.extractionMethod()));
                int order = 0;
                for (PastPaperDraftDto.MarkPointDraft mp : mine) {
                    QuestionPart part = resolvePart(version, mp.questionRef());
                    scheme.addPoint(new MarkPoint(scheme, part, mp.questionRef(), order++,
                            mp.text(), Math.max(mp.marks(), 1), mp.acceptance(),
                            mp.confidence()));
                    pointCount++;
                }
            }
        }

        log.info("ingested paper {}: {} questions, {} parts, {} mark points (all SUGGESTED)",
                paper.id(), questionCount, partCount, pointCount);
        return new IngestionSummary(paper.id(), questionCount, partCount, pointCount);
    }

    /** points whose questionRef matches the question number exactly or "N-x" parts */
    private static List<PastPaperDraftDto.MarkPointDraft> pointsForQuestion(
            List<PastPaperDraftDto.MarkPointDraft> points, String questionNumber) {
        String prefix = questionNumber + "-";
        return points.stream()
                .filter(p -> p.questionRef() != null
                        && (p.questionRef().equals(questionNumber)
                            || p.questionRef().startsWith(prefix)))
                .toList();
    }

    /** resolve the part a mark point targets: "3-a" → part "a" of question 3 */
    private static QuestionPart resolvePart(QuestionVersion version, String questionRef) {
        if (questionRef == null) return null;
        int dash = questionRef.indexOf('-');
        if (dash < 0 || dash + 1 >= questionRef.length()) return null; // question-level point
        String label = questionRef.substring(dash + 1);
        return version.parts().stream()
                .filter(p -> label.equals(p.label()))
                .findFirst()
                .orElse(null);
    }

    private Subject resolveSubject(PastPaperDraftDto.PaperMeta meta) {
        String code = subjectCode(meta);
        return subjects.findByCode(code).orElseGet(() -> {
            CurriculumVersion cv = curriculumVersions.findAllByOrderByCreatedAtDesc().stream()
                    .filter(c -> meta.board() != null && meta.board().equalsIgnoreCase(c.board())
                            && meta.qualification() != null
                            && meta.qualification().equalsIgnoreCase(c.qualification()))
                    .findFirst()
                    .orElseGet(() -> curriculumVersions.save(new CurriculumVersion(
                            nullSafe(meta.board(), "unknown-board"),
                            nullSafe(meta.qualification(), "unknown"),
                            code + "-INGEST",
                            (meta.qualification() == null ? "" : meta.qualification()) + " "
                                    + (meta.subject() == null ? "" : meta.subject())
                                    + " (ingested, pending review)",
                            CurriculumVersion.Status.DRAFT)));
            return subjects.save(new Subject(cv, code,
                    nullSafe(meta.subject(), "unknown subject")));
        });
    }

    private UUID createIngestionAnchor(PastPaperDraftDto.PaperMeta meta, Subject subject,
                                       UUID ingestedBy) {
        // Anchor identity = ONE PER PAPER (javadoc contract above): printed paper
        // code + session label when available. knowledge_nodes.code is VARCHAR(40):
        // cap deterministically with an 8-hex hash suffix when the raw identity is
        // longer. A paper without a session label cannot reach here (identity gate);
        // a paper without a printed paper code falls back to its session label, and
        // the find-or-create below keeps papers of the same session (e.g. 1C + 2C
        // drafts that printed no code) on ONE shared placeholder — the r1 collision
        // shape — instead of failing on uq_knowledge_node_code.
        String rawIdentity = (meta.paperCode() == null
                ? (meta.sessionLabel() == null
                        ? UUID.randomUUID().toString().substring(0, 8)
                        : meta.sessionLabel().replaceAll("\\W+", "").toUpperCase())
                : (meta.paperCode().replaceAll("\\W+", "")
                        + (meta.sessionLabel() == null ? ""
                        : meta.sessionLabel().replaceAll("\\W+", "").toUpperCase())));
        String anchorCode = "ING-" + rawIdentity;
        if (anchorCode.length() > 40) {
            anchorCode = anchorCode.substring(0, 31) + "-"
                    + String.format("%08x", anchorCode.hashCode());
        }
        KnowledgeNode subjectRoot = subject.knowledgeNodeId() != null
                ? knowledgeNodes.findById(subject.knowledgeNodeId()).orElse(null)
                : null;
        // Find-or-create, mirroring CurriculumIngestionService's anchor handling:
        // two papers of the SAME exam session derive the same anchor code (e.g.
        // June 2013 and its regional retake 2013-Jun-R both print "Summer 2013"
        // on the cover → ING-SUMMER2013). The anchor is a placeholder ("remap
        // during review", Master Spec §7 — the pipeline never guesses curriculum
        // placement), so one anchor per exam session is the correct sharing;
        // an unconditional insert violates uq_knowledge_node_code and fails the
        // whole batch. Found anchors keep their original provenance untouched.
        final String anchorCodeForInsert = anchorCode; // lambda capture (reassigned above)
        KnowledgeNode anchor = knowledgeNodes.findByCode(anchorCode).orElseGet(
                () -> knowledgeNodes.save(new KnowledgeNode(
                        anchorCodeForInsert, NodeType.TOPIC,
                        "Ingestion anchor: " + nullSafe(meta.paperCode(), meta.unit()),
                        "Auto-created topic for past-paper ingestion — remap during review "
                                + "(Master Spec §7: pipeline never guesses curriculum placement).",
                        KnowledgeNode.ValidationStatus.UNVALIDATED,
                        "past-paper draft " + nullSafe(meta.paperCode(), "unknown"),
                        ingestedBy == null ? "ingestion-v1" : ingestedBy.toString())));
        // Same sharing rule for the PART_OF edge (uq_edge is source+target+type):
        // skip only when this exact (anchor → subjectRoot) edge already exists.
        if (subjectRoot != null
                && knowledgeEdges.findBySourceIdAndRelationType(
                                anchor.id(), com.syllabai.knowledge.RelationType.PART_OF)
                        .map(e -> !subjectRoot.id().equals(e.target().id()))
                        .orElse(true)) {
            knowledgeEdges.save(new KnowledgeEdge(anchor, subjectRoot,
                    com.syllabai.knowledge.RelationType.PART_OF, null,
                    "ingestion anchor under subject root",
                    KnowledgeNode.ValidationStatus.UNVALIDATED,
                    "past-paper draft", "ingestion-v1"));
        }
        return anchor.id();
    }

    private static String subjectCode(PastPaperDraftDto.PaperMeta meta) {
        String raw = (meta.qualification() == null ? "" : meta.qualification())
                + "-" + (meta.subject() == null ? "GEN" : meta.subject());
        String sanitized = raw.replaceAll("\\W+", "").toUpperCase();
        return sanitized.substring(0, Math.min(20, sanitized.length()));
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Human-readable paper title built ONLY from the printed identity components,
     * skipping absent ones: e.g. "IGCSE Chemistry 4CH1/1C June 2020" or, when the
     * parser could not recover a paper code, " Summer 2013".trim(). Never embeds
     * the literal string "null" (the previous concatenation did exactly that).
     */
    private static String paperTitle(PastPaperDraftDto.PaperMeta meta, String paperCode,
                                     String sessionLabel) {
        String unit = paperCode != null ? paperCode : meta.unit();
        String title = Stream.of(meta.qualification(), meta.subject(), unit, sessionLabel)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" ")).strip();
        return title.isEmpty() ? "past paper" : title;
    }

    /** null-safe truncation for VARCHAR columns fed from untrusted draft input */
    private static String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    /**
     * @param paperId      the created exam paper
     * @param questions    created questions
     * @param parts        created parts
     * @param markPoints   created mark points
     */
    public record IngestionSummary(UUID paperId, int questions, int parts, int markPoints) {
    }
}
