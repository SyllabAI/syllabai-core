package com.syllabai.knowledge;

import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge-graph read endpoints (Master Spec §22).
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeGraphService graph;

    public KnowledgeController(KnowledgeGraphService graph) {
        this.graph = graph;
    }

    @GetMapping("/nodes/{id}")
    public NodeView node(@PathVariable UUID id) {
        return NodeView.flat(graph.node(id));
    }

    @GetMapping("/nodes/{id}/tree")
    public NodeView tree(@PathVariable UUID id,
                         @RequestParam(defaultValue = "false") boolean includeMisconceptions) {
        return includeMisconceptions ? graph.treeWithMisconceptions(id) : graph.tree(id);
    }

    @GetMapping("/nodes/{id}/prerequisites")
    public List<PrerequisiteView> prerequisites(@PathVariable UUID id) {
        return graph.prerequisiteChain(id);
    }

    @GetMapping("/nodes/{id}/misconceptions")
    public List<NodeView> misconceptions(@PathVariable UUID id) {
        return graph.misconceptions(id);
    }
}
