package com.syllabai.tutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Citation resolution (T-024, Master Spec §17): renders evidence items into
 * concrete, resolvable citations — "Mark scheme — p6" with a deep link into
 * the content API, or "Specification — Topic 3 (U1-T3)" with a link into the
 * KG API. Never a vague "according to the syllabus".
 *
 * <p>Deep links currently target the teacher content API (the only document
 * read surface today); the learner-facing citation view arrives with the
 * tutor UI (T-025).</p>
 */
@Component
public class SimpleCitationResolver implements CitationResolver {

    @Override
    public List<Citation> resolve(List<EvidenceItem> evidence) {
        List<Citation> citations = new ArrayList<>(evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceItem item = evidence.get(i);
            citations.add(new Citation(i + 1, labelOf(item), item.source().name(),
                    item.documentId(), item.pageStart(), item.nodeId(), deepLinkOf(item)));
        }
        return citations;
    }

    private String labelOf(EvidenceItem item) {
        return switch (item.source()) {
            case MARK_SCHEME -> "Mark scheme" + pageSuffix(item);
            case QUESTION_PAPER -> "Question paper" + pageSuffix(item);
            case SYLLABUS -> "Specification" + pageSuffix(item);
            case OTHER -> "Source document" + pageSuffix(item);
            case KNOWLEDGE_NODE -> String.format(Locale.ROOT, "Specification topic %s — %s",
                    item.nodeCode(), item.nodeTitle());
            case LEARNER_WORK -> "Your submitted answer";
            case NOTE -> "Revision notes" + pageSuffix(item);
            case TEXTBOOK -> "Textbook" + pageSuffix(item);
            case CARD -> "Question card";
        };
    }

    private String pageSuffix(EvidenceItem item) {
        if (item.pageStart() == null) {
            return "";
        }
        if (item.pageEnd() != null && !item.pageEnd().equals(item.pageStart())) {
            return " — pp" + item.pageStart() + "–" + item.pageEnd();
        }
        return " — p" + item.pageStart();
    }

    private String deepLinkOf(EvidenceItem item) {
        if (item.source() == EvidenceItem.EvidenceSource.KNOWLEDGE_NODE) {
            return "/api/v1/knowledge/nodes/" + item.nodeId();
        }
        if (item.documentRowId() == null) {
            // no content-store row backs this item (e.g. the CLA's synthesized
            // question-stem / assessment-model scheme-point evidence) — an
            // honest null link, never a fabricated path
            return null;
        }
        String link = "/api/v1/teacher/content/documents/" + item.documentRowId();
        return item.pageStart() == null ? link : link + "?page=" + item.pageStart();
    }
}
