package com.syllabai.cla;

import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side ResourceContext resolution (CLA contract §1, §5): the client
 * passes opaque references; the server resolves them fail-closed.
 *
 * <p>Resolution order (contract §5): resolve context → validate gate → scope.
 * Concretely:</p>
 * <ol>
 *   <li>the root must BE a subject root — CLA v1 requires a subject-rooted
 *       context, which is what makes curriculum identity and subject
 *       isolation resolvable server-side;</li>
 *   <li>the topic must live inside the root's PART_OF subtree (hard subject
 *       isolation — the same rule as the Smart Lesson surface; a topic from
 *       another subject is a 404, not a silent cross-subject hop);</li>
 *   <li>the validation gate is the serving boundary's own rule: only
 *       VALIDATED curriculum nodes may anchor CLA evidence assembly
 *       (SUGGESTED/UNVALIDATED content is invisible to the CLA exactly as it
 *       is to the Tutor — and fails indistinguishably from an unresolvable
 *       reference, so no validation-state oracle is created);</li>
 *   <li>curriculum identity comes from the owning subject's version, never
 *       from the client.</li>
 * </ol>
 */
@Service
public class ClaContextResolver {

    private final KnowledgeGraphService graph;
    private final KnowledgeNodeRepository nodes;
    private final SubjectRepository subjects;

    public ClaContextResolver(KnowledgeGraphService graph,
                              KnowledgeNodeRepository nodes,
                              SubjectRepository subjects) {
        this.graph = graph;
        this.nodes = nodes;
        this.subjects = subjects;
    }

    /**
     * Resolve a KG_TOPIC context. Every failure mode is a
     * {@link NotFoundException}: an unresolvable reference is a 404, never a
     * best-effort guess (contract §1.1).
     */
    @Transactional(readOnly = true)
    public ResourceContext resolveKgTopic(UUID rootId, UUID topicNodeId, UUID learnerId) {
        Subject subject = subjects.findByKnowledgeNodeId(rootId)
                .orElseThrow(() -> new NotFoundException("curriculum subject root", rootId));

        // registry over the subject subtree (same scoping pattern as Smart Lesson)
        Map<UUID, NodeView> byId = new HashMap<>();
        collect(graph.tree(rootId), byId);

        NodeView topic = byId.get(topicNodeId);
        if (topic == null) {
            throw new NotFoundException("curriculum topic in this subject", topicNodeId);
        }

        KnowledgeNode node = nodes.findById(topicNodeId)
                .orElseThrow(() -> new NotFoundException("curriculum topic", topicNodeId));
        if (node.validationStatus() != KnowledgeNode.ValidationStatus.VALIDATED) {
            // validation gate (contract §1.2): fail-closed, indistinguishable
            // from unresolvable — no validation-state existence oracle
            throw new NotFoundException("validated curriculum topic", topicNodeId);
        }

        CurriculumVersion version = subject.curriculumVersion();
        return new ResourceContext(
                ResourceContext.Kind.KG_TOPIC,
                topicNodeId,
                rootId,
                subject.code(),
                node.code(),
                node.title(),
                new ResourceContext.CurriculumVersionInfo(
                        version.code(), version.board(), version.qualification(),
                        version.status().name()),
                node.validationStatus(),
                learnerId,
                Instant.now());
    }

    private void collect(NodeView node, Map<UUID, NodeView> byId) {
        byId.put(node.id(), node);
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                collect(child, byId);
            }
        }
    }
}
