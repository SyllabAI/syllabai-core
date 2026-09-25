package com.syllabai.knowledge.dto;

import com.syllabai.knowledge.KnowledgeNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Node projection; {@code children} is populated only in tree views.
 *
 * <p>{@code applicability} (T-C24, V39) carries the node's official paper/unit/tier
 * scope verbatim when the node is a spec point the seeded store scoped (papers /
 * double_award_shared / rule for 4CH1); {@code null} on every other node. Curriculum
 * metadata only — never answer content.</p>
 */
public record NodeView(
        UUID id, String code, String type, String title, String description,
        String validationStatus, String provenance,
        Map<String, Object> applicability,
        List<NodeView> children) {

    public static NodeView flat(KnowledgeNode n) {
        return new NodeView(n.id(), n.code(), n.nodeType().name(), n.title(), n.description(),
                n.validationStatus().name(), n.provenance(), n.applicability(), List.of());
    }
}
