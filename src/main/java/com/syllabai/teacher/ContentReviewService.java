package com.syllabai.teacher;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
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

    public ContentReviewService(ExamPaperRepository examPapers,
                                QuestionVersionRepository questionVersions,
                                MarkSchemeRepository markSchemes,
                                MarkPointRepository markPoints) {
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
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
}
