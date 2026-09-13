package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.teacher.ConceptGraphSnapshotLoader.ConceptGraphSnapshot;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V15 concept-graph snapshot loader: the six pinned classpath files parse into
 * the expected substrate (the settled T-C11 store + the c09 phase-1 4CH1
 * curriculum), the counts fail-closed on their store facts, the frozen five
 * (3 pilot HOLDs + 2 REVIEW_REQUIRED) are provably excluded, parsing is
 * deterministic, and any tamper with the pinned bytes fails loudly.
 */
class ConceptGraphSnapshotLoaderTest {

    private final ConceptGraphSnapshotLoader loader = new ConceptGraphSnapshotLoader();

    @Test
    @DisplayName("the pinned snapshots parse into the settled substrate with exact store counts")
    void loadsTheSettledSubstrate() {
        ConceptGraphSnapshot snapshot = loader.load();

        assertThat(snapshot.sections()).hasSize(ConceptGraphSnapshotLoader.SECTION_COUNT);
        assertThat(snapshot.subsections()).hasSize(ConceptGraphSnapshotLoader.SUBSECTION_COUNT);
        assertThat(snapshot.specPoints()).hasSize(ConceptGraphSnapshotLoader.SPEC_POINT_COUNT);
        assertThat(snapshot.practicals()).hasSize(ConceptGraphSnapshotLoader.PRACTICAL_COUNT);
        assertThat(snapshot.conceptNodes()).hasSize(ConceptGraphSnapshotLoader.CONCEPT_NODE_COUNT);
        assertThat(snapshot.anchorEdges()).hasSize(ConceptGraphSnapshotLoader.ANCHOR_EDGE_COUNT);
        assertThat(snapshot.validatedSemanticEdges())
                .hasSize(ConceptGraphSnapshotLoader.VALIDATED_SEMANTIC_EDGE_COUNT);
    }

    @Test
    @DisplayName("Case D substrate: only HUMAN_VALIDATED semantic edges — the frozen five never load")
    void onlyValidatedSemanticEdgesLoad() {
        // the real frozen five: 2 REVIEW_REQUIRED + 3 pilot operator HOLDs (byte-verified
        // against the settled store) — MIS-EQ-SUBSCRIPT also carries a VALIDATED
        // MISCONCEPTION_OF edge, so its frozen REMEDIATED_BY discriminates cleanly
        Set<String> frozen = Set.of(
                "REQUIRES_PREREQUISITE|4CH1-CON-CRYSTALLISATION|4CH1-CON-SOLUTION",
                "REQUIRES_PREREQUISITE|4CH1-CON-GAS-VOL-CALC|4CH1-CON-AVOGADRO-LAW",
                "REQUIRES_PREREQUISITE|4CH1-CON-REACTING-MASS|4CH1-CON-EQ-SYMBOL",
                "EXPLAINED_BY|4CH1-CON-MOLAR-GAS-VOL|4CH1-CON-AVOGADRO-LAW",
                "REMEDIATED_BY|4CH1-MIS-EQ-SUBSCRIPT|4CH1-CON-CONSERVATION-MASS");

        for (ConceptGraphSnapshot.ValidatedEdge edge : loader.load().validatedSemanticEdges()) {
            assertThat(frozen).doesNotContain(
                    edge.relation() + "|" + edge.source() + "|" + edge.target());
        }
        // relation families of the 153 validated edges (store facts)
        assertThat(snapshotRelations("REQUIRES_PREREQUISITE")).isEqualTo(112);
        assertThat(snapshotRelations("REMEDIATED_BY")).isEqualTo(14);
        assertThat(snapshotRelations("WRONG_ANSWER_PATTERN")).isEqualTo(13);
        assertThat(snapshotRelations("EXPLAINED_BY")).isEqualTo(9);
        assertThat(snapshotRelations("COMMONLY_CONFUSED_WITH")).isEqualTo(2);
        assertThat(snapshotRelations("MISCONCEPTION_OF")).isEqualTo(2);
        assertThat(snapshotRelations("RELATED_TO")).isEqualTo(1);
    }

    @Test
    @DisplayName("Case A substrate: real settled identities are present and carry provenance")
    void realSettledIdentitiesPresent() {
        ConceptGraphSnapshot snapshot = loader.load();

        // the session-55 Case-A/B fixtures — the exact chain the learner NBA uses
        assertThat(snapshot.conceptNodes()).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo("4CH1-CON-BOND-ENERGY-CALC");
            assertThat(n.family()).isEqualTo("CONCEPT");
        });
        assertThat(snapshot.conceptNodes()).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo("4CH1-CON-COVALENT-BOND");
        });
        assertThat(snapshot.conceptNodes()).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo("4CH1-MIS-BOND-ENERGY-COUNT");
            assertThat(n.family()).isEqualTo("MISCONCEPTION");
        });
        // the validated prerequisite chain + remediation edge
        assertThat(snapshot.validatedSemanticEdges()).anySatisfy(e -> {
            assertThat(e.source()).isEqualTo("4CH1-CON-BOND-ENERGY-CALC");
            assertThat(e.relation()).isEqualTo("REQUIRES_PREREQUISITE");
            assertThat(e.target()).isEqualTo("4CH1-CON-COVALENT-BOND");
        });
        assertThat(snapshot.validatedSemanticEdges()).anySatisfy(e -> {
            assertThat(e.source()).isEqualTo("4CH1-MIS-BOND-ENERGY-COUNT");
            assertThat(e.relation()).isEqualTo("REMEDIATED_BY");
            assertThat(e.target()).isEqualTo("4CH1-CON-BOND-ENERGY-CALC");
        });
        // every validated edge carries the operator provenance line
        assertThat(snapshot.validatedSemanticEdges())
                .allSatisfy(e -> assertThat(e.provenance())
                        .contains("t-c11:settled")
                        .contains("validated_by:operator"));
    }

    @Test
    @DisplayName("Case B substrate: S2/S4 spec points exist but carry no settled concepts")
    void unsettledSectionsCarryNoConcepts() {
        ConceptGraphSnapshot snapshot = loader.load();

        // sections 2 and 4 are part of the official curriculum (real rows)…
        assertThat(snapshot.specPoints())
                .anySatisfy(sp -> assertThat(sp.code()).isEqualTo("4CH1-2.1"));
        assertThat(snapshot.sections()).anySatisfy(s -> {
            assertThat(s.code()).isEqualTo("4CH1-S4");
        });
        // …but no concept anchors anywhere outside S1/S3 (the settled slices)
        assertThat(snapshot.anchorEdges())
                .allSatisfy(a -> assertThat(a.specPointCode()).matches("4CH1-[13]\\..*"));
    }

    @Test
    @DisplayName("deterministic: two loads are identical (orderings included)")
    void deterministicParse() {
        ConceptGraphSnapshot first = loader.load();
        ConceptGraphSnapshot second = loader.load();
        assertThat(first.sections()).containsExactlyElementsOf(second.sections());
        assertThat(first.subsections()).containsExactlyElementsOf(second.subsections());
        assertThat(first.specPoints()).containsExactlyElementsOf(second.specPoints());
        assertThat(first.practicals()).containsExactlyElementsOf(second.practicals());
        assertThat(first.conceptNodes()).containsExactlyElementsOf(second.conceptNodes());
        assertThat(first.anchorEdges()).containsExactlyElementsOf(second.anchorEdges());
        assertThat(first.validatedSemanticEdges())
                .containsExactlyElementsOf(second.validatedSemanticEdges());
    }

    @Test
    @DisplayName("fail-closed: tampered snapshot bytes refuse to load")
    void tamperedSnapshotFails() {
        // structural tamper through the parse seam: one demoted status ⇒ 152
        byte[] edges = readResourceBytes(ConceptGraphSnapshotLoader.CONCEPT_EDGES_RESOURCE);
        String mutated = new String(edges, java.nio.charset.StandardCharsets.UTF_8)
                .replaceFirst("validation_status: HUMAN_VALIDATED", "validation_status: SUGGESTED");
        byte[] specPoints = readResourceBytes(ConceptGraphSnapshotLoader.SPEC_POINTS_RESOURCE);
        byte[] topics = readResourceBytes(ConceptGraphSnapshotLoader.TOPICS_RESOURCE);
        byte[] relationships = readResourceBytes(ConceptGraphSnapshotLoader.RELATIONSHIPS_RESOURCE);
        byte[] concepts = readResourceBytes(ConceptGraphSnapshotLoader.CONCEPTS_RESOURCE);
        byte[] practicals = readResourceBytes(ConceptGraphSnapshotLoader.PRACTICALS_RESOURCE);

        assertThatThrownBy(() -> loader.parse(specPoints, topics, relationships, concepts,
                mutated.getBytes(java.nio.charset.StandardCharsets.UTF_8), practicals))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validated semantic edge count");
    }

    @Test
    @DisplayName("fail-closed: hash-pin drift refuses to load")
    void hashDriftFails() {
        byte[] tampered = "meta: {}\nspecification_points: []".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ConceptGraphSnapshotLoader.requireSha256(
                ConceptGraphSnapshotLoader.SPEC_POINTS_RESOURCE, tampered,
                ConceptGraphSnapshotLoader.SPEC_POINTS_SHA256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot drift");
    }

    @Test
    @DisplayName("no duplicate codes anywhere in the substrate")
    void noDuplicateCodes() {
        ConceptGraphSnapshot snapshot = loader.load();
        Set<String> codes = new HashSet<>();
        for (ConceptGraphSnapshot.SpecPoint sp : snapshot.specPoints()) {
            assertThat(codes.add(sp.code())).isTrue();
        }
        for (ConceptGraphSnapshot.ConceptNodeRecord n : snapshot.conceptNodes()) {
            assertThat(codes.add(n.code())).isTrue();
        }
        for (ConceptGraphSnapshot.Practical p : snapshot.practicals()) {
            assertThat(codes.add(p.code())).isTrue();
        }
    }

    private long snapshotRelations(String relation) {
        return loader.load().validatedSemanticEdges().stream()
                .filter(e -> relation.equals(e.relation()))
                .count();
    }

    private static byte[] readResourceBytes(String path) {
        try (var in = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("test resource missing: " + path, e);
        }
    }
}
