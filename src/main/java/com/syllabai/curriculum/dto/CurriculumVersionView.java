package com.syllabai.curriculum.dto;

import com.syllabai.curriculum.CurriculumVersion;
import java.util.UUID;

public record CurriculumVersionView(
        UUID id, String board, String qualification, String code, String title,
        CurriculumVersion.Status status) {

    public static CurriculumVersionView from(CurriculumVersion v) {
        return new CurriculumVersionView(
                v.id(), v.board(), v.qualification(), v.code(), v.title(), v.status());
    }
}
