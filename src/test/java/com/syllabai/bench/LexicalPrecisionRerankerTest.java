package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.tutor.EvidenceItem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arm-D reranker's unit contract (T-C63): pre-registered deterministic
 * lexical-precision scoring — IDF-weighted query-term saturation over the
 * fused pool, no length normalization (b=0), stable ties, never invents or
 * drops candidates, rerankScore stamped on every item.
 */
class LexicalPrecisionRerankerTest {

    private static EvidenceItem item(String content) {
        return new EvidenceItem(EvidenceItem.EvidenceSource.OTHER, content,
                null, "doc-1", null, null, null, null, null, null, null,
                null, null, List.of(), List.of(), 0.0, 0.0, null);
    }

    private static List<String> contents(List<EvidenceItem> items) {
        return items.stream().map(EvidenceItem::content).toList();
    }

    @Test
    @DisplayName("query-term matches lift a candidate: rarer terms weigh more (IDF)")
    void idfWeightedLift() {
        // pool of three; "electrolysis" appears in only one candidate (rare → high IDF),
        // "the" appears in all three (df = N → IDF ≈ ln(1 + 0.5/N), near-zero)
        List<EvidenceItem> pool = List.of(
                item("the reaction rate increases with temperature"),
                item("the electrolysis of molten compounds releases the metal"),
                item("the periodic table groups elements by atomic number"));
        List<EvidenceItem> reranked = new LexicalPrecisionReranker().rerank(
                "how does electrolysis release the metal", pool);
        assertThat(contents(reranked).get(0))
                .isEqualTo("the electrolysis of molten compounds releases the metal");
        assertThat(reranked.get(0).rerankScore()).isPositive();
    }

    @Test
    @DisplayName("saturation: repeated terms help, but with diminishing returns (k1=1.2)")
    void saturationDoesNotRunAway() {
        List<EvidenceItem> pool = List.of(
                item("rates rates rates rates rates rates rates rates"),
                item("rates and collisions and energy"));
        List<EvidenceItem> reranked = new LexicalPrecisionReranker().rerank("rates", pool);
        assertThat(contents(reranked).get(0)).isEqualTo("rates rates rates rates rates rates rates rates");
        // both mention the term; the tf-saturated winner must not approach
        // the sum of ALL pool idf mass — the score is bounded by idf·(k1+1)
        assertThat(reranked.get(0).rerankScore()).isLessThan(3.0);
    }

    @Test
    @DisplayName("ties keep the incoming fused order (stable, deterministic)")
    void tiesAreStable() {
        // neither candidate shares any query term → equal 0.0 scores → fused order stands
        List<EvidenceItem> pool = List.of(item("alpha beta"), item("gamma delta"));
        List<EvidenceItem> once = new LexicalPrecisionReranker().rerank("nothing matches here", pool);
        List<EvidenceItem> twice = new LexicalPrecisionReranker().rerank("nothing matches here", pool);
        assertThat(contents(once)).containsExactly("alpha beta", "gamma delta");
        assertThat(contents(once)).isEqualTo(contents(twice));
    }

    @Test
    @DisplayName("never invents or drops: output is a permutation of the input")
    void permutationOnly() {
        List<EvidenceItem> pool = List.of(
                item("titration curves and indicators"),
                item("the mole and avogadro constant"),
                item("ionic equations state symbols"));
        List<EvidenceItem> reranked = new LexicalPrecisionReranker().rerank(
                "titration indicators ionic equations", pool);
        assertThat(reranked).hasSameSizeAs(pool);
        assertThat(Set.copyOf(contents(reranked))).isEqualTo(Set.copyOf(contents(pool)));
    }

    @Test
    @DisplayName("rerankScore stamped on every item, including pass-throughs")
    void scoresStamped() {
        LexicalPrecisionReranker reranker = new LexicalPrecisionReranker();
        assertThat(reranker.rerank("any query", List.of())).isEmpty();
        List<EvidenceItem> blank = reranker.rerank("   ", List.of(item("content")));
        assertThat(blank).hasSize(1);
        assertThat(blank.get(0).rerankScore()).isEqualTo(0.0);
        List<EvidenceItem> scored = reranker.rerank("content", List.of(item("content")));
        assertThat(scored.get(0).rerankScore()).isPositive();
    }

    @Test
    @DisplayName("tokenizer: lowercase alphanumeric runs; spec-code fragments are ordinary tokens")
    void tokenizerContract() {
        assertThat(LexicalPrecisionReranker.tokenize("Electrolysis of Al2O3 — cryolite!"))
                .containsExactly("electrolysis", "of", "al2o3", "cryolite");
        assertThat(LexicalPrecisionReranker.tokenize("spec 4CH1-2.5C covers rates"))
                .containsExactly("spec", "4ch1", "2", "5c", "covers", "rates");
        assertThat(LexicalPrecisionReranker.tokenize(null)).isEmpty();
        assertThat(LexicalPrecisionReranker.tokenize("")).isEmpty();
    }

    @Test
    @DisplayName("null candidates is a port-contract violation (fail-closed)")
    void nullCandidatesFailClosed() {
        assertThatThrownBy(() -> new LexicalPrecisionReranker().rerank("q", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
