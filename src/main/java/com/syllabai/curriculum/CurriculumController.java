package com.syllabai.curriculum;

import com.syllabai.curriculum.dto.CurriculumVersionView;
import com.syllabai.curriculum.dto.SubjectView;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curriculum metadata endpoints (Master Spec §22). The topic tree itself is served
 * by the knowledge module from the knowledge graph.
 */
@RestController
@RequestMapping("/api/v1/curriculum")
public class CurriculumController {

    private final CurriculumVersionRepository curriculumVersions;
    private final SubjectRepository subjects;

    public CurriculumController(CurriculumVersionRepository curriculumVersions,
                                SubjectRepository subjects) {
        this.curriculumVersions = curriculumVersions;
        this.subjects = subjects;
    }

    @GetMapping("/versions")
    @Transactional(readOnly = true)
    public List<CurriculumVersionView> versions(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return (includeArchived
                ? curriculumVersions.findAllByOrderByCreatedAtDesc()
                : curriculumVersions.findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status.ACTIVE))
                .stream().map(CurriculumVersionView::from).toList();
    }

    @GetMapping("/subjects")
    @Transactional(readOnly = true)
    public List<SubjectView> subjects(@RequestParam(required = false) UUID versionId) {
        List<Subject> result = versionId == null
                ? subjects.findAllByOrderByCode()
                : subjects.findByCurriculumVersionIdOrderByCode(versionId);
        return result.stream().map(SubjectView::from).toList();
    }

    @GetMapping("/subjects/{id}")
    @Transactional(readOnly = true)
    public SubjectView subject(@PathVariable UUID id) {
        return subjects.findById(id).map(SubjectView::from)
                .orElseThrow(() -> new NotFoundException("subject", id));
    }
}
