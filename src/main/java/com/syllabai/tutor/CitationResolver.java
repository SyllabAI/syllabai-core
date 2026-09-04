package com.syllabai.tutor;

import java.util.List;

/**
 * Port: citation resolution (Master Spec §17, T-024). Evidence items carry
 * provenance; this port renders them into the citation view the tutor answer
 * exposes — verbatim source references and deep links, never vague
 * attribution ("Chemistry spec, Topic 3" with a resolvable pointer, not
 * "according to the syllabus").
 */
public interface CitationResolver {

    /**
     * @param evidence the answer's evidence set (citation index = list order)
     * @return one citation per evidence item, same order
     */
    List<Citation> resolve(List<EvidenceItem> evidence);

    /**
     * @param index      1-based citation number ([n] markers in the answer)
     * @param label      human-readable source label
     * @param sourceType evidence source type
     * @param documentId canonical document id (null for KG citations)
     * @param page       source page when known
     * @param nodeId     KG node id for spec-topic citations (null for chunks)
     * @param deepLink   resolvable link into the content/KG API
     */
    record Citation(int index, String label, String sourceType, String documentId,
                    Integer page, java.util.UUID nodeId, String deepLink) {
    }
}
