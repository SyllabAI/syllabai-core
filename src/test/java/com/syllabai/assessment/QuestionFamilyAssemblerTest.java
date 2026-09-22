package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.dto.QuestionFamilyView;
import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole-question family rule (session-121) — the server-side port of the
 * session-120 client module {@code exam-families.ts}, which was verified
 * against the production corpus. These tests pin the behaviours the demo's
 * serving logic depends on: family grouping, member order (default + the
 * pinned interleaved orders), SME page order across sources, and the
 * non-corpus fallback.
 */
class QuestionFamilyAssemblerTest {

    private final QuestionFamilyAssembler assembler = new QuestionFamilyAssembler();

    // ── fixture helpers ───────────────────────────────────────────────────

    private StudentQuestionView row(String externalRef, String type, int marks, int difficulty) {
        return new StudentQuestionView(
                UUID.randomUUID(), externalRef, type, "stem of " + externalRef, marks,
                difficulty, 60, "State", UUID.randomUUID(), null,
                List.of(), List.of(), List.of());
    }

    private List<String> refs(List<QuestionFamilyView> units) {
        return units.stream().map(QuestionFamilyView::key).toList();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a question the import split into part rows serves as ONE whole question")
    void splitFamilyReassembles() {
        // the session-120 production defect family: ionic bonding q1, split
        // into four MCQ part rows; States of matter served only -p4
        List<StudentQuestionView> rows = List.of(
                row("sme-eq-1-6-ionic-bonding-q1-p1", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-6-ionic-bonding-q1-p2", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-6-ionic-bonding-q1-p3", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-6-ionic-bonding-q1-p4", "MCQ_SINGLE", 3, 2));

        List<QuestionFamilyView> units = assembler.assemble(rows);

        assertThat(units).hasSize(1);
        QuestionFamilyView unit = units.get(0);
        assertThat(unit.key()).isEqualTo("sme-eq-1-6-ionic-bonding-q1");
        assertThat(unit.ref()).isEqualTo("sme-eq-1-6-ionic-bonding-q1");
        assertThat(unit.multi()).isTrue();
        assertThat(unit.type()).isEqualTo("MCQ");
        assertThat(unit.marks()).isEqualTo(6); // the SME question's total
        assertThat(unit.difficulty()).isEqualTo(2);
        assertThat(unit.parts()).extracting(StudentQuestionView::externalRef)
                .containsExactly("sme-eq-1-6-ionic-bonding-q1-p1",
                        "sme-eq-1-6-ionic-bonding-q1-p2",
                        "sme-eq-1-6-ionic-bonding-q1-p3",
                        "sme-eq-1-6-ionic-bonding-q1-p4");
    }

    @Test
    @DisplayName("a mixed MCQ+structured family orders p1..pN then -s by default, and is STRUCTURED")
    void mixedFamilyDefaultOrder() {
        List<StudentQuestionView> rows = new ArrayList<>(List.of(
                row("sme-eq-1-3-separating-mixtures-q7-s", "STRUCTURED", 3, 3),
                row("sme-eq-1-3-separating-mixtures-q7-p2", "MCQ_SINGLE", 1, 3),
                row("sme-eq-1-3-separating-mixtures-q7-p1", "MCQ_SINGLE", 1, 3)));
        // feed shuffled: assembly must order, not trust input order

        QuestionFamilyView unit = assembler.assemble(rows).get(0);

        assertThat(unit.type()).isEqualTo("STRUCTURED"); // mixed question
        assertThat(unit.marks()).isEqualTo(5);
        assertThat(unit.parts()).extracting(StudentQuestionView::externalRef)
                .containsExactly("sme-eq-1-3-separating-mixtures-q7-p1",
                        "sme-eq-1-3-separating-mixtures-q7-p2",
                        "sme-eq-1-3-separating-mixtures-q7-s");
    }

    @Test
    @DisplayName("the pinned interleaved families keep their SME part order")
    void pinnedInterleavedOrder() {
        // one of the 23 production-pinned families: -p1, -s, -p2
        List<StudentQuestionView> rows = List.of(
                row("sme-eq-1-1-states-of-matter-q16-p1", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-1-states-of-matter-q16-p2", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-1-states-of-matter-q16-s", "STRUCTURED", 2, 2));

        QuestionFamilyView unit = assembler.assemble(rows).get(0);

        assertThat(unit.parts()).extracting(StudentQuestionView::externalRef)
                .containsExactly("sme-eq-1-1-states-of-matter-q16-p1",
                        "sme-eq-1-1-states-of-matter-q16-s",
                        "sme-eq-1-1-states-of-matter-q16-p2");
    }

    @Test
    @DisplayName("families sort in SME page order: numeric-aware source, then question number")
    void smePageOrder() {
        // 1-10 must sort AFTER 1-2 (numeric, not lexicographic); within a
        // source q1 before q2; feed everything shuffled
        List<StudentQuestionView> rows = List.of(
                row("sme-eq-2-1-group-1-alkali-metals-q1", "MCQ_SINGLE", 1, 2),
                row("sme-eq-1-2-elements-compounds-and-mixtures-q3", "MCQ_SINGLE", 1, 1),
                row("sme-eq-1-10-calculations-involving-masses-q1", "MCQ_SINGLE", 1, 3),
                row("sme-eq-1-2-elements-compounds-and-mixtures-q1", "MCQ_SINGLE", 1, 1));

        assertThat(refs(assembler.assemble(rows))).containsExactly(
                "sme-eq-1-2-elements-compounds-and-mixtures-q1",
                "sme-eq-1-2-elements-compounds-and-mixtures-q3",
                "sme-eq-1-10-calculations-involving-masses-q1",
                "sme-eq-2-1-group-1-alkali-metals-q1");
    }

    @Test
    @DisplayName("rows outside the SME ref convention are their own families, served after the corpus")
    void nonSmeRowsAreOwnFamilies() {
        StudentQuestionView seed = row(null, "MCQ_SINGLE", 1, 3);
        StudentQuestionView pastPaper = row("WCH11-2022-01-03a", "MCQ_SINGLE", 2, 3);
        StudentQuestionView sme = row("sme-eq-1-1-states-of-matter-q1", "MCQ_SINGLE", 1, 2);

        List<QuestionFamilyView> units = assembler.assemble(List.of(seed, pastPaper, sme));

        assertThat(units).hasSize(3);
        assertThat(refs(units)).containsExactly(
                "sme-eq-1-1-states-of-matter-q1", // corpus first
                seed.id().toString(),             // then the non-corpus rows
                pastPaper.id().toString());
        assertThat(units.get(0).multi()).isFalse();
        assertThat(units.get(0).ref()).isEqualTo("sme-eq-1-1-states-of-matter-q1");
        assertThat(units.get(1).key()).isEqualTo(seed.id().toString());
        assertThat(units.get(1).multi()).isFalse();
        assertThat(units.get(1).ref()).isNull(); // the row's own ref (null here)
        assertThat(units.get(2).key()).isEqualTo(pastPaper.id().toString());
        assertThat(units.get(2).ref()).isEqualTo("WCH11-2022-01-03a");
    }

    @Test
    @DisplayName("familyKey exposes the grouping identity for the taxonomy census")
    void familyKeyMatchesAssembly() {
        UUID rowId = UUID.randomUUID();
        // a part row keys to its family base…
        assertThat(assembler.familyKey("sme-eq-1-6-ionic-bonding-q1-p4", rowId))
                .isEqualTo("sme-eq-1-6-ionic-bonding-q1");
        // …a no-suffix corpus row is its own family…
        assertThat(assembler.familyKey("sme-eq-1-1-states-of-matter-q1", rowId))
                .isEqualTo("sme-eq-1-1-states-of-matter-q1");
        // …and anything else keys by row id
        assertThat(assembler.familyKey("WCH11-2022-01-03a", rowId)).isEqualTo(rowId.toString());
        assertThat(assembler.familyKey(null, rowId)).isEqualTo(rowId.toString());
    }
}
