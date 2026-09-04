package com.syllabai.tutor;

import java.util.List;
import java.util.UUID;

/**
 * Port: grounded-context assembly (Master Spec §13, T-024). Combines the KG
 * context (topics, prerequisites, misconceptions), the fused evidence set
 * and the learner model (mastery, active misconceptions, fluency gaps) into
 * the structured context a tutor generator consumes. Learner-state awareness
 * is what separates KA-RAG from a generic chatbot (Paper B §3.3) — but the
 * policy layer (which intervention to choose) is T-026, not this port.
 */
public interface ContextAssembler {

    /**
     * @param knowledge  KG retrieval result for the query
     * @param evidence   fused + reranked evidence the answer will cite
     * @param learnerId  the asking learner (null = anonymous/preview: no learner brief)
     * @return the assembled tutor context
     */
    TutorContext assemble(KnowledgeRetriever.KnowledgeContext knowledge,
                          List<EvidenceItem> evidence, UUID learnerId);

    /**
     * @param learnerBrief  rendered learner-state summary (mastery/misconceptions/gaps)
     * @param knowledgeBrief rendered KG summary (topics, prerequisites, misconceptions)
     * @param evidence      the evidence set, citation-numbered order preserved
     */
    record TutorContext(String learnerBrief, String knowledgeBrief,
                        List<EvidenceItem> evidence) {
    }
}
