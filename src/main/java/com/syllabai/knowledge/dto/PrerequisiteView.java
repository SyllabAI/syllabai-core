package com.syllabai.knowledge.dto;

import com.syllabai.knowledge.KnowledgeGraphRepository;
import java.util.UUID;

/**
 * A prerequisite in the remediation chain: {@code depth} = 1 means "direct prerequisite",
 * deeper values are transitive prerequisites of prerequisites.
 */
public record PrerequisiteView(
        UUID id, String code, String type, String title, int depth) {

    public static PrerequisiteView from(KnowledgeGraphRepository.PrerequisiteWithDepth p) {
        return new PrerequisiteView(
                p.node().id(), p.node().code(), p.node().nodeType().name(),
                p.node().title(), p.depth());
    }
}
