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
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher content-validation workflow (Master Spec §7): ingested content is
 * SUGGESTED and never serves until validated here. Reviewers approve/reject papers,
 * question versions and mark schemes, and author the deterministic acceptance
 * criteria the Smart Mark pipeline relies on — the pipeline never invents them.
 */
@Service
public class ContentReviewService {

    private static final Logger log = LoggerFactory.getLogger(ContentReviewService.class);

    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final MarkPointRepository markPoints;
    private final SubjectRepository subjects;

    public ContentReviewService(ExamPaperRepository examPapers,
                                QuestionVersionRepository questionVersions,
                                MarkSchemeRepository markSchemes,
                                MarkPointRepository markPoints,
                                SubjectRepository subjects) {
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
        this.subjects = subjects;
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
        paper.validate();
        log.info("exam paper {} validated by teacher", paperId);
        return paper;
    }

    @Transactional
    public ExamPaper rejectPaper(UUID paperId) {
        ExamPaper paper = examPapers.findById(paperId)
                .orElseThrow(() -> new NotFoundException("exam paper", paperId));
        paper.reject();
        return paper;
    }

    @Transactional
    public QuestionVersion validateQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        version.validate();
        return version;
    }

    @Transactional
    public QuestionVersion rejectQuestionVersion(UUID versionId) {
        QuestionVersion version = questionVersions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("question version", versionId));
        version.reject();
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
        scheme.validate();
        log.info("mark scheme {} validated ({} criteria updates)",
                schemeId, criteriaUpdates == null ? 0 : criteriaUpdates.size());
        return scheme;
    }

    @Transactional
    public MarkScheme rejectMarkScheme(UUID schemeId) {
        MarkScheme scheme = markSchemes.findById(schemeId)
                .orElseThrow(() -> new NotFoundException("mark scheme", schemeId));
        scheme.reject();
        return scheme;
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
                new PaperReviewView.PaperHeader(paper.id(), paper.title(), paper.paperCode(),
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
                points, options, parts);
    }

    /**
     * Teacher-facing review projection of a paper: header + every question
     * version with its full answer key and mark-scheme state.
     */
    public record PaperReviewView(PaperHeader paper, List<VersionReviewView> versions) {

        public record PaperHeader(UUID id, String title, String paperCode, String sessionLabel,
                                  String board, String qualification, String validationState) {
        }
    }

    public record VersionReviewView(
            UUID versionId, UUID questionId, String externalRef, String type,
            String stem, int marks, int version, String validationState, String commandWord,
            UUID schemeId, String schemeState,
            List<PointReview> points, List<OptionReview> options, List<PartReview> parts) {

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
