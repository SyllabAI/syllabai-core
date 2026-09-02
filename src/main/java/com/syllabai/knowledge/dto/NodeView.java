package com.syllabai.knowledge.dto;

import com.syllabai.knowledge.KnowledgeNode;
import java.util.List;
import java.util.UUID;

/**
 * Node projection; {@code children} is populated only in tree views.
 */
public record NodeView(
        UUID id, String code, String type, String title, String description,
        String validationStatus, String provenance,
        List<NodeView> children) {

    public static NodeView flat(KnowledgeNode n) {
        return new NodeView(n.id(), n.code(), n.nodeType().name(), n.title(), n.description(),
                n.validationStatus().name(), n.provenance(), List.of());
    }
}
