package com.syllabai.curriculum.dto;

import com.syllabai.curriculum.Subject;
import java.util.UUID;

public record SubjectView(
        UUID id, String code, String name, UUID knowledgeNodeId,
        CurriculumVersionView curriculumVersion) {

    public static SubjectView from(Subject s) {
        return new SubjectView(
                s.id(), s.code(), s.name(), s.knowledgeNodeId(),
                CurriculumVersionView.from(s.curriculumVersion()));
    }
}
