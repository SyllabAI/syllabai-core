package com.syllabai.sme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * DTO tree for the SME exam-question corpus package
 * ({@code sme-question-package/1.0}, ADR-026) produced by
 * {@code scripts/s104_build_question_package.py} in syllabai-resources.
 *
 * <p>Every field the ingest service validates is represented; unknown JSON
 * properties are ignored so the package format can grow additively.</p>
 */
public final class SmeQuestionPackageDtos {

    private SmeQuestionPackageDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Package(
            String packageVersion,
            String corpusVersion,
            String generatedAt,
            String source,
            Map<String, Integer> counts,
            List<Question> questions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Question(
            String externalRef,
            String questionType,            // MCQ_SINGLE | STRUCTURED
            String stem,
            int marks,
            int difficulty,                // 1-5
            String difficultySource,       // "SME"
            int expectedTimeSeconds,
            String commandWord,
            String primaryTopicCode,       // KG node code, e.g. 4CH1-S1-c
            List<String> secondaryTopicCodes,
            List<SpecPoint> specPoints,
            Map<String, Object> sourcePaper,
            String smeSet,
            String smeDifficulty,
            List<Option> options,
            String solutionMd,
            List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpecPoint(
            String code,
            String role,                   // PRIMARY | SECONDARY
            String provenance) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Option(
            String label,
            String text,
            boolean isCorrect) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(
            String label,
            String prompt,
            int marks,
            String commandWord,
            String solutionMd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IngestSummary(
            int questions,
            int mcq,
            int structured,
            int parts,
            int options,
            int markPoints,
            int specPointMappings,
            int topicMappings,
            int assets,
            int deactivated,
            String corpusVersion) {
    }
}
