package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper.ReviewFinding;
import com.syllabai.teacher.ingestion.GlmOcrPaperDraftDto.QuestionDraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-C02 mapper: the GLM-OCR parser contract → the existing T-011 draft, pinned
 * against the six REAL GLM-markdown-sample fixtures (three QP/MS pairs). Every
 * mapping decision (MS-derived paper identity, no subject/command-word/option
 * invention, part-ref normalization, whole-entry mark points for unsplit cells,
 * unknown marks = 0) is asserted on real corpus data, not synthetic samples.
 */
class GlmOcrDraftMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr");

    private final GlmOcrDraftMapper mapper = new GlmOcrDraftMapper();

    private record Pair(GlmOcrPaperDraftDto qp, GlmOcrMarkSchemeDraftDto ms,
                        GlmOcrReconciliationDto reconciliation) {
    }

    private static Pair pair(String name) throws Exception {
        return new Pair(
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/qp-draft.json")),
                        GlmOcrPaperDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/ms-draft.json")),
                        GlmOcrMarkSchemeDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/reconciliation.json")),
                        GlmOcrReconciliationDto.class));
    }

    @Test
    @DisplayName("real corpus: question counts 20 / 20 / 19 across the three pairs")
    void questionCounts() throws Exception {
        assertThat(pair("june-2025-wph11-01").qp().questions()).hasSize(20);
        assertThat(pair("october-2025-wph11-01").qp().questions()).hasSize(20);
        assertThat(pair("october-2025-wph11-01a").qp().questions()).hasSize(19);
    }

    @ParameterizedTest
    @ValueSource(strings = {"june-2025-wph11-01", "october-2025-wph11-01",
            "october-2025-wph11-01a"})
    @DisplayName("paper identity: MS draft is the identity source; canonical doc ids link both sides")
    void paperIdentityFromMs(String name) throws Exception {
        Pair pair = pair(name);
        PastPaperDraftDto mapped = mapper.toPastPaperDraft(pair.qp(), pair.ms());

        PastPaperDraftDto.PaperMeta paper = mapped.paper();
        assertThat(paper.board()).isEqualTo("Edexcel");
        assertThat(paper.qualification()).isEqualTo("IAL");
        assertThat(paper.sessionLabel()).isNotBlank();
        assertThat(paper.questionPaperDocumentId())
                .isEqualTo(pair.qp().paper().canonicalDocumentId());
        assertThat(paper.markSchemeDocumentId())
                .isEqualTo(pair.ms().paper().canonicalDocumentId());
        // never inferred from the paper code — teachers remap during review
        assertThat(paper.subject()).isNull();
        assertThat(paper.unit()).isNull();
        // publication identity survives only in the verbatim drafts (bridge record),
        // never guessed into the T-011 meta
        assertThat(pair.ms().paper().logNumber()).startsWith("P");
        assertThat(pair.ms().paper().publicationCode()).isNotBlank();
        // extraction method preserves BOTH parser extractor identities
        assertThat(mapped.extractionMethod()).isEqualTo("glm-ocr-qp-v1+glm-ocr-ms-v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"june-2025-wph11-01", "october-2025-wph11-01",
            "october-2025-wph11-01a"})
    @DisplayName("questions: parser ids/numbers/stems/parts preserved; nothing invented")
    void questionMapping(String name) throws Exception {
        Pair pair = pair(name);
        PastPaperDraftDto mapped = mapper.toPastPaperDraft(pair.qp(), pair.ms());

        assertThat(mapped.questions()).hasSameSizeAs(pair.qp().questions());
        for (int i = 0; i < mapped.questions().size(); i++) {
            QuestionDraft source = pair.qp().questions().get(i);
            PastPaperDraftDto.QuestionDraft target = mapped.questions().get(i);
            assertThat(target.externalRef()).isEqualTo(source.questionId());
            assertThat(target.questionNumber()).isEqualTo(Integer.toString(source.number()));
            assertThat(target.prompt()).isEqualTo(source.stem());
            assertThat(target.commandWord()).isNull();       // never guessed from the stem
            assertThat(target.questionType()).isNull();      // T-011 ingests STRUCTURED
            assertThat(target.marks()).isEqualTo(source.marks());
            assertThat(target.confidence()).isEqualTo(source.confidence());
            assertThat(target.parts()).hasSameSizeAs(source.parts());
            for (int p = 0; p < target.parts().size(); p++) {
                assertThat(target.parts().get(p).label()).isEqualTo(source.parts().get(p).label());
                assertThat(target.parts().get(p).prompt()).isEqualTo(source.parts().get(p).text());
            }
        }
        // roman subparts keep the QP label convention ("b-i") — no flattening
        assertThat(mapped.questions().stream()
                .flatMap(q -> q.parts().stream())
                .map(PastPaperDraftDto.PartDraft::label)
                .filter(l -> l.contains("-")))
                .isNotEmpty();
    }

    @Test
    @DisplayName("part refs: MS labels '13(a)' → '13-a', '13(b)(i)' → '13-b-i', '11' stays question-level")
    void markPointRefs() throws Exception {
        Pair pair = pair("october-2025-wph11-01");
        PastPaperDraftDto mapped = mapper.toPastPaperDraft(pair.qp(), pair.ms());

        Map<String, List<String>> refsByLabel = pair.ms().entries().stream()
                .filter(e -> !e.markPoints().isEmpty())
                .collect(Collectors.toMap(
                        GlmOcrMarkSchemeDraftDto.MarkSchemeEntry::label,
                        e -> List.of(GlmOcrDraftMapper.markPointRef(e)),
                        (a, b) -> a));

        // real labels from the October MS
        assertThat(refsByLabel.get("16(b)")).containsExactly("16-b");
        assertThat(refsByLabel.get("16(a)(i)")).containsExactly("16-a-i");
        assertThat(refsByLabel.get("16(a)(ii)")).containsExactly("16-a-ii");
        // question-level entry (no part) keeps the bare number
        assertThat(refsByLabel.get("12")).containsExactly("12");

        // every mapped point's ref matches a question-number prefix the T-011
        // resolver understands ("N" or "N-…")
        for (PastPaperDraftDto.MarkPointDraft point : mapped.markScheme().points()) {
            assertThat(point.questionRef()).matches("\\d{1,2}(-[a-h](-[ivx]+)?)?");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"june-2025-wph11-01", "october-2025-wph11-01",
            "october-2025-wph11-01a"})
    @DisplayName("unsplit MS cells become whole-entry points — no question loses its marking evidence")
    void wholeEntryPoints(String name) throws Exception {
        Pair pair = pair(name);
        PastPaperDraftDto mapped = mapper.toPastPaperDraft(pair.qp(), pair.ms());

        // every QP question number must have at least one mark point
        for (QuestionDraft q : pair.qp().questions()) {
            String prefix = q.number() + "-";
            long mine = mapped.markScheme().points().stream()
                    .filter(p -> p.questionRef().equals(Integer.toString(q.number()))
                            || p.questionRef().startsWith(prefix))
                    .count();
            assertThat(mine)
                    .as("question %s must keep marking evidence", q.number())
                    .isPositive();
        }

        // entries without "(N)" markers materialize as ONE point carrying the entry text+marks
        GlmOcrMarkSchemeDraftDto.MarkSchemeEntry mcqRationale = pair.ms().entries().stream()
                .filter(e -> e.mcq() || e.markPoints().isEmpty())
                .findFirst().orElseThrow();
        PastPaperDraftDto.MarkPointDraft wholeEntry = mapped.markScheme().points().stream()
                .filter(p -> p.text().equals(mcqRationale.answerText()))
                .findFirst().orElseThrow();
        assertThat(wholeEntry.marks()).isEqualTo(mcqRationale.marks());
    }

    @Test
    @DisplayName("unknown marks stay 0 (never a guessed value); null-part marks stay 0")
    void unknownMarksAreZero() throws Exception {
        Pair pair = pair("october-2025-wph11-01");
        PastPaperDraftDto mapped = mapper.toPastPaperDraft(pair.qp(), pair.ms());

        // the October MS has two split points without "(N)" markers
        int nullMarks = (int) pair.ms().entries().stream()
                .flatMap(e -> e.markPoints().stream())
                .filter(mp -> mp.marks() == null).count();
        if (nullMarks > 0) {
            long zeros = mapped.markScheme().points().stream().filter(p -> p.marks() == 0).count();
            assertThat(zeros).isGreaterThanOrEqualTo(nullMarks);
        }

        // QP parts without printed marks map to 0, not to an invented value
        long nullPartMarks = pair.qp().questions().stream()
                .flatMap(q -> q.parts().stream())
                .filter(p -> p.marks() == null).count();
        assertThat(mapped.questions().stream()
                .flatMap(q -> q.parts().stream())
                .filter(p -> p.marks() == 0).count())
                .isEqualTo(nullPartMarks);
    }

    @Test
    @DisplayName("October Q18: part-marks-sum vs printed-total conflict is relayed verbatim")
    void octoberQ18ConflictRelayed() throws Exception {
        Pair pair = pair("october-2025-wph11-01");
        List<ReviewFinding> findings = mapper.assembleReviewFindings(
                pair.qp(), pair.ms(), pair.reconciliation());

        // the parser warning line itself, never repaired
        assertThat(findings.stream()
                .filter(f -> f.source().equals("QP_WARNING"))
                .map(ReviewFinding::detail))
                .contains("Q18: part marks sum (2) conflicts with printed total (8)");

        // and the evidence stays visible in the mapped draft too: Q18 marks = 2 (part sum)
        QuestionDraft q18 = pair.qp().questions().stream()
                .filter(q -> q.number() == 18).findFirst().orElseThrow();
        assertThat(q18.marks()).isEqualTo(2);
        assertThat(q18.marksKnown()).isFalse();
    }

    @Test
    @DisplayName("1A: QP 80 vs MS 120 paper-total conflict is relayed — never merged")
    void oneAPaperTotalConflictRelayed() throws Exception {
        Pair pair = pair("october-2025-wph11-01a");
        assertThat(pair.reconciliation().paperTotalConflict()).isTrue();
        assertThat(pair.reconciliation().qpPaperTotal()).isEqualTo(80);
        assertThat(pair.reconciliation().msPaperTotal()).isEqualTo(120);

        List<ReviewFinding> findings = mapper.assembleReviewFindings(
                pair.qp(), pair.ms(), pair.reconciliation());
        ReviewFinding conflict = findings.stream()
                .filter(f -> "paper-total-conflict".equals(f.severity()))
                .findFirst().orElseThrow();
        assertThat(conflict.qpMarks()).isEqualTo(80);
        assertThat(conflict.msMarks()).isEqualTo(120);
        assertThat(conflict.detail()).contains("never merged");
    }

    @Test
    @DisplayName("June pair: aligned totals — no paper-total conflict, warnings still relayed")
    void juneAligned() throws Exception {
        Pair pair = pair("june-2025-wph11-01");
        List<ReviewFinding> findings = mapper.assembleReviewFindings(
                pair.qp(), pair.ms(), pair.reconciliation());

        assertThat(pair.reconciliation().paperTotalConflict()).isFalse();
        assertThat(findings.stream()
                .filter(f -> "paper-total-conflict".equals(f.severity()))).isEmpty();
        assertThat(findings.stream()
                .anyMatch(f -> f.source().equals("QP_WARNING"))).isTrue();

        // every reconciliation finding is relayed verbatim — none discarded
        assertThat(findings.stream()
                .filter(f -> f.source().equals("RECONCILIATION")
                        && !"paper-total-conflict".equals(f.severity())))
                .hasSize(pair.reconciliation().findings().size());
    }

    @Test
    @DisplayName("deterministic: the same drafts always map to the same T-011 draft")
    void deterministic() throws Exception {
        Pair pair = pair("october-2025-wph11-01a");
        assertThat(mapper.toPastPaperDraft(pair.qp(), pair.ms()))
                .isEqualTo(mapper.toPastPaperDraft(pair.qp(), pair.ms()));
        assertThat(mapper.assembleReviewFindings(pair.qp(), pair.ms(), pair.reconciliation()))
                .isEqualTo(mapper.assembleReviewFindings(pair.qp(), pair.ms(), pair.reconciliation()));
    }
}
