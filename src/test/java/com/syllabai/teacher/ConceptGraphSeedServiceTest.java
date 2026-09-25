package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V15 teacher concept-graph seed: the pinned 4CH1 snapshot materializes into
 * the existing KG tables deterministically and idempotently, with the store's
 * epistemic statuses intact. Covers the operator's test matrix:
 * A (4CH1 activation from real curriculum rows), B (unsettled SP fabricates
 * nothing), C (canonical concepts reused, never duplicated), D (HOLD/RR/
 * unvalidated edges excluded), E (idempotent re-run = same graph state),
 * G (cross-subject isolation), plus the loud-conflict contract.
 */
class ConceptGraphSeedServiceTest {

    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);

    private final ConceptGraphSeedService service = new ConceptGraphSeedService(
            new ConceptGraphSnapshotLoader(), curriculumVersions, subjects,
            knowledgeNodes, knowledgeEdges);

    // in-memory KG — the state the idempotency and identity tests reason over
    private final Map<String, KnowledgeNode> nodeByCode = new HashMap<>();
    private final Map<UUID, KnowledgeNode> nodeById = new HashMap<>();
    private final Map<String, KnowledgeEdge> edgeByKey = new HashMap<>();
    private final Set<UUID> everSavedNodeIds = new HashSet<>();
    private final Set<UUID> everSavedEdgeIds = new HashSet<>();
    private final AtomicReference<CurriculumVersion> versionHolder = new AtomicReference<>();
    private final AtomicReference<Subject> subjectHolder = new AtomicReference<>();

    {
        when(curriculumVersions.save(any(CurriculumVersion.class))).thenAnswer(inv -> {
            CurriculumVersion v = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            versionHolder.set(v);
            return v;
        });
        when(curriculumVersions.findByBoardAndQualificationAndCode(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(versionHolder.get()));
        when(subjects.save(any(Subject.class))).thenAnswer(inv -> {
            Subject s = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            subjectHolder.set(s);
            return s;
        });
        when(subjects.findByCurriculumVersionIdAndCode(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(subjectHolder.get()));
        when(knowledgeNodes.save(any(KnowledgeNode.class))).thenAnswer(inv -> {
            KnowledgeNode n = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            nodeByCode.put(n.code(), n);
            nodeById.put(n.id(), n);
            everSavedNodeIds.add(n.id());
            return n;
        });
        when(knowledgeNodes.findByCode(any())).thenAnswer(
                inv -> Optional.ofNullable(nodeByCode.get(inv.getArgument(0, String.class))));
        when(knowledgeNodes.findById(any())).thenAnswer(
                inv -> Optional.ofNullable(nodeById.get(inv.getArgument(0, UUID.class))));
        when(knowledgeEdges.save(any(KnowledgeEdge.class))).thenAnswer(inv -> {
            KnowledgeEdge e = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            edgeByKey.put(edgeKey(e.sourceId(), e.targetId(), e.relationType()), e);
            everSavedEdgeIds.add(e.id());
            return e;
        });
        when(knowledgeEdges.findBySourceIdAndTargetIdAndRelationType(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(
                        edgeByKey.get(edgeKey(inv.getArgument(0, UUID.class),
                                inv.getArgument(1, UUID.class),
                                inv.getArgument(2, RelationType.class)))));
    }

    private static String edgeKey(UUID source, UUID target, RelationType relation) {
        return source + "|" + target + "|" + relation;
    }

    private KnowledgeEdge findEdge(String sourceCode, String targetCode, RelationType relation) {
        KnowledgeNode source = nodeByCode.get(sourceCode);
        KnowledgeNode target = nodeByCode.get(targetCode);
        return source == null || target == null ? null
                : edgeByKey.get(edgeKey(source.id(), target.id(), relation));
    }

    // ── A: 4CH1 activation ─────────────────────────────────────────

    @Test
    @DisplayName("Case A: known 4CH1 spec points produce the expected teacher KG relationships")
    void activatesRealCurriculumRows() {
        ConceptGraphSeedService.SeedSummary summary = service.activate(UUID.randomUUID());

        // curriculum identity: version ACTIVE, subject linked to the 4CH1 root
        assertThat(versionHolder.get().code()).isEqualTo("4CH1-2017");
        assertThat(versionHolder.get().status()).isEqualTo(CurriculumVersion.Status.ACTIVE);
        assertThat(subjectHolder.get().code()).isEqualTo("4CH1");
        assertThat(subjectHolder.get().knowledgeNodeId()).isEqualTo(summary.rootNodeId());
        assertThat(nodeByCode.get("4CH1").id()).isEqualTo(summary.rootNodeId());

        // exact store counts land in the KG
        assertThat(summary.sections()).isEqualTo(4);
        assertThat(summary.subsections()).isEqualTo(28);
        assertThat(summary.specPoints()).isEqualTo(182);
        assertThat(summary.practicals()).isEqualTo(12);
        assertThat(summary.conceptNodes()).isEqualTo(113);
        assertThat(summary.validatedSemanticEdges()).isEqualTo(153);
        assertThat(nodeByCode).hasSize(1 + 4 + 28 + 182 + 12 + 113);
        assertThat(everSavedEdgeIds).hasSize(4 + 28 + 182 + 12 + 117 + 153);

        // the real settled chain: SP 4CH1-3.7C carries CON-BOND-ENERGY-CALC,
        // which REQUIRES_PREREQUISITE CON-COVALENT-BOND; MIS-BOND-ENERGY-COUNT
        // is REMEDIATED_BY the bond-energy concept (validated, operator provenance)
        assertThat(findEdge("4CH1-CON-BOND-ENERGY-CALC", "4CH1-CON-COVALENT-BOND",
                RelationType.REQUIRES_PREREQUISITE)).isNotNull();
        KnowledgeEdge remediation = findEdge("4CH1-MIS-BOND-ENERGY-COUNT",
                "4CH1-CON-BOND-ENERGY-CALC", RelationType.REMEDIATED_BY);
        assertThat(remediation).isNotNull();
        assertThat(remediation.validationStatus()).isEqualTo(KnowledgeNode.ValidationStatus.VALIDATED);
        assertThat(remediation.provenance()).contains("t-c11:settled")
                .contains("validated_by:operator");

        // statuses preserve the store's epistemic state
        assertThat(nodeByCode.get("4CH1-1.36").validationStatus())
                .isEqualTo(KnowledgeNode.ValidationStatus.VALIDATED);          // official anchor
        assertThat(nodeByCode.get("4CH1-CON-BOND-ENERGY-CALC").validationStatus())
                .isEqualTo(KnowledgeNode.ValidationStatus.SUGGESTED);          // graph layer
        assertThat(nodeByCode.get("4CH1-CON-BOND-ENERGY-CALC").nodeType())
                .isEqualTo(NodeType.CONCEPT);
        assertThat(nodeByCode.get("4CH1-MIS-BOND-ENERGY-COUNT").nodeType())
                .isEqualTo(NodeType.MISCONCEPTION);
        // concept anchored under its SP (SUGGESTED anchor, per the store)
        assertThat(findEdge("4CH1-CON-BOND-ENERGY-CALC", "4CH1-3.7C",
                RelationType.PART_OF).validationStatus())
                .isEqualTo(KnowledgeNode.ValidationStatus.SUGGESTED);
        // practical node anchored under its SP (official spec content)
        assertThat(nodeByCode.get("4CH1-PR-01").nodeType()).isEqualTo(NodeType.SUBTOPIC);
        assertThat(findEdge("4CH1-PR-01", "4CH1-1.7C", RelationType.PART_OF)).isNotNull();
    }

    // ── B: unsettled specification point ───────────────────────────

    @Test
    @DisplayName("Case B: a valid unsettled 4CH1 SP lands as official structure with NO fabricated graph data")
    void unsettledSpecPointFabricatesNothing() {
        service.activate(UUID.randomUUID());

        // 4CH1-2.1 is a real S2 spec point: the official anchor exists…
        KnowledgeNode sp = nodeByCode.get("4CH1-2.1");
        assertThat(sp).isNotNull();
        assertThat(sp.validationStatus()).isEqualTo(KnowledgeNode.ValidationStatus.VALIDATED);
        assertThat(findEdge("4CH1-2.1", "4CH1-S2-a", RelationType.PART_OF)).isNotNull();
        // …but nothing graph-derived hangs under it and no semantic edge touches it
        assertThat(edgeByKey.values().stream()
                .filter(e -> e.relationType() != RelationType.PART_OF
                        && (e.sourceId().equals(sp.id()) || e.targetId().equals(sp.id())))
                .findAny()).isEmpty();
        assertThat(nodeByCode.keySet().stream()
                .filter(code -> code.startsWith("4CH1-CON-") || code.startsWith("4CH1-MIS-"))
                .map(nodeByCode::get)
                .filter(n -> findEdge(n.code(), "4CH1-2.1", RelationType.PART_OF) != null))
                .isEmpty();
    }

    // ── C + E: canonical identity + idempotency ────────────────────

    @Test
    @DisplayName("Case E: re-activating reuses every canonical node and edge — zero new rows")
    void idempotentReActivation() {
        UUID operator = UUID.randomUUID();
        service.activate(operator);
        Map<String, KnowledgeNode> firstNodes = Map.copyOf(nodeByCode);
        Map<String, KnowledgeEdge> firstEdges = Map.copyOf(edgeByKey);
        int savedNodes = everSavedNodeIds.size();
        int savedEdges = everSavedEdgeIds.size();

        ConceptGraphSeedService.SeedSummary second = service.activate(operator);

        assertThat(second.nodesCreated()).isZero();
        assertThat(second.edgesCreated()).isZero();
        assertThat(second.alreadyActive()).isTrue();
        assertThat(nodeByCode).isEqualTo(firstNodes);       // no new nodes, none replaced
        assertThat(edgeByKey).isEqualTo(firstEdges);
        assertThat(everSavedNodeIds).hasSize(savedNodes);   // no duplicate canonical concepts
        assertThat(everSavedEdgeIds).hasSize(savedEdges);   // no duplicate graph edges
    }

    @Test
    @DisplayName("T-C24: spec-point nodes carry the snapshot applicability verbatim; every other row stays null")
    void applicabilityLandsOnCreatedSpecPoints() {
        service.activate(UUID.randomUUID());

        KnowledgeNode sp = nodeByCode.get("4CH1-1.1");
        assertThat(sp.applicability()).isNotNull();
        assertThat(sp.applicability())
                .containsEntry("papers", List.of("1C", "2C"))
                .containsEntry("double_award_shared", true);
        KnowledgeNode cPoint = nodeByCode.get("4CH1-3.7C");
        assertThat(cPoint.applicability())
                .containsEntry("papers", List.of("2C"))
                .containsEntry("double_award_shared", false);

        // the store scopes spec points only — root, structure and concept rows stay null
        assertThat(nodeByCode.get("4CH1").applicability()).isNull();
        assertThat(nodeByCode.get("4CH1-CON-COVALENT-BOND").applicability()).isNull();
        // the create path sets the field BEFORE the single save: one saved id per node, not two
        assertThat(everSavedNodeIds).hasSize(1 + 4 + 28 + 182 + 12 + 113);
    }

    @Test
    @DisplayName("T-C24: a pre-V39 spec-point row is backfilled on reuse; the next run writes nothing")
    void applicabilityBackfillIsOneTimeAndIdempotent() {
        // a spec-point row from a pre-V39 seed: correct provenance, null applicability
        knowledgeNodes.save(new KnowledgeNode("4CH1-1.1", NodeType.SUBTOPIC,
                "recall that the universe is made of matter", "pre-V39 row (null applicability)",
                KnowledgeNode.ValidationStatus.VALIDATED,
                ConceptGraphSeedService.STRUCTURE_PROVENANCE, "prior-run"));

        service.activate(UUID.randomUUID());

        // the same canonical row (same seed provenance — no re-seed) now carries the scope object
        assertThat(nodeByCode.get("4CH1-1.1").applicability())
                .containsEntry("papers", List.of("1C", "2C"))
                .containsEntry("double_award_shared", true);

        // re-run: content-equal applicability ⇒ zero writes, graph state byte-stable
        Map<String, KnowledgeNode> firstNodes = Map.copyOf(nodeByCode);
        int savedNodes = everSavedNodeIds.size();   // a re-save would mint a fresh id (TestIds)
        service.activate(UUID.randomUUID());
        assertThat(everSavedNodeIds).hasSize(savedNodes);
        assertThat(nodeByCode).isEqualTo(firstNodes);
    }

    @Test
    @DisplayName("Case C: a pre-existing canonical concept is reused, not duplicated")
    void canonicalIdentityReused() {
        // a canonical concept row already exists with the seed provenance
        // (e.g. seeded by a prior activation on another environment)
        KnowledgeNode preExisting = knowledgeNodes.save(new KnowledgeNode(
                "4CH1-CON-COVALENT-BOND", NodeType.CONCEPT, "Covalent bond",
                "pre-existing canonical concept",
                KnowledgeNode.ValidationStatus.SUGGESTED,
                ConceptGraphSeedService.CONCEPT_PROVENANCE, "prior-run"));
        UUID preExistingId = preExisting.id();

        service.activate(UUID.randomUUID());

        KnowledgeNode after = nodeByCode.get("4CH1-CON-COVALENT-BOND");
        assertThat(after.id()).isEqualTo(preExistingId);      // same canonical row
        assertThat(nodeByCode.values().stream()
                .filter(n -> "4CH1-CON-COVALENT-BOND".equals(n.code()))
                .count()).isEqualTo(1);                       // never duplicated
        assertThat(after.provenance()).isEqualTo(ConceptGraphSeedService.CONCEPT_PROVENANCE);
    }

    @Test
    @DisplayName("a canonical code held by foreign provenance fails loudly instead of re-seeding")
    void foreignProvenanceConflicts() {
        knowledgeNodes.save(new KnowledgeNode("4CH1-CON-COVALENT-BOND", NodeType.CONCEPT,
                "Covalent bond", "someone else's row", KnowledgeNode.ValidationStatus.VALIDATED,
                "hand-curated:teacher-42", "teacher-42"));

        assertThatThrownBy(() -> service.activate(UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("4CH1-CON-COVALENT-BOND")
                .hasMessageContaining("different provenance");
    }

    // ── D: validation filtering ────────────────────────────────────

    @Test
    @DisplayName("Case D: the frozen five (3 HOLD + 2 REVIEW_REQUIRED) never materialize")
    void heldAndReviewRequiredEdgesExcluded() {
        service.activate(UUID.randomUUID());

        // exactly 153 semantic edges landed (store fact), none of the frozen five
        assertThat(edgeByKey.values().stream()
                .filter(e -> e.relationType() != RelationType.PART_OF)
                .count()).isEqualTo(153);
        assertThat(findEdge("4CH1-CON-CRYSTALLISATION", "4CH1-CON-SOLUTION",
                RelationType.REQUIRES_PREREQUISITE)).isNull();          // REVIEW_REQUIRED
        assertThat(findEdge("4CH1-CON-GAS-VOL-CALC", "4CH1-CON-AVOGADRO-LAW",
                RelationType.REQUIRES_PREREQUISITE)).isNull();          // REVIEW_REQUIRED
        assertThat(findEdge("4CH1-CON-REACTING-MASS", "4CH1-CON-EQ-SYMBOL",
                RelationType.REQUIRES_PREREQUISITE)).isNull();          // pilot HOLD
        assertThat(findEdge("4CH1-CON-MOLAR-GAS-VOL", "4CH1-CON-AVOGADRO-LAW",
                RelationType.EXPLAINED_BY)).isNull();                   // pilot HOLD
        assertThat(findEdge("4CH1-MIS-EQ-SUBSCRIPT", "4CH1-CON-CONSERVATION-MASS",
                RelationType.REMEDIATED_BY)).isNull();                  // pilot HOLD
        // …while MIS-EQ-SUBSCRIPT's VALIDATED MISCONCEPTION_OF edge did land
        assertThat(findEdge("4CH1-MIS-EQ-SUBSCRIPT", "4CH1-CON-EQ-SYMBOL",
                RelationType.MISCONCEPTION_OF)).isNotNull();
    }

    // ── G: cross-subject isolation ─────────────────────────────────

    @Test
    @DisplayName("Case G: a foreign subject's tree is untouched — no cross-subject contamination")
    void crossSubjectIsolation() {
        // the V6-style IAL tree pre-exists
        KnowledgeNode chm = knowledgeNodes.save(new KnowledgeNode("CHM", NodeType.SUBJECT,
                "Chemistry", "IAL root", KnowledgeNode.ValidationStatus.VALIDATED,
                "Edexcel IAL specification 2018", "seed-v6"));
        KnowledgeNode wch11 = knowledgeNodes.save(new KnowledgeNode("WCH11-T3.2", NodeType.SUBTOPIC,
                "Covalent bonding", "IAL subtopic", KnowledgeNode.ValidationStatus.UNVALIDATED,
                "Edexcel IAL specification topic list", "seed-v6"));
        UUID wch11Id = wch11.id();
        knowledgeEdges.save(new KnowledgeEdge(wch11, chm, RelationType.PART_OF, null,
                null, KnowledgeNode.ValidationStatus.VALIDATED, "spec structure", "seed-v6"));
        int v6EdgeCount = edgeByKey.size();
        Map<String, KnowledgeNode> v6Nodes = Map.copyOf(nodeByCode);

        service.activate(UUID.randomUUID());

        // V6 rows untouched: same ids, same content, no new edges into them
        assertThat(nodeByCode.get("CHM").id()).isEqualTo(chm.id());
        assertThat(nodeByCode.get("WCH11-T3.2").id()).isEqualTo(wch11Id);
        v6Nodes.forEach((code, node) -> {
            if (!code.startsWith("4CH1")) {
                assertThat(nodeByCode.get(code)).isEqualTo(node);
            }
        });
        // every semantic edge the seed wrote has 4CH1-prefixed endpoints on both sides
        edgeByKey.values().stream()
                .filter(e -> e.provenance() != null && (e.provenance().startsWith("t-c11")
                        || e.provenance().startsWith("spec:4CH1")))
                .forEach(e -> {
                    assertThat(nodeById.get(e.sourceId()).code()).startsWith("4CH1");
                    assertThat(nodeById.get(e.targetId()).code()).startsWith("4CH1");
                });
        // and no 4CH1 PART_OF edge ever attached to the IAL tree
        assertThat(edgeByKey.values().stream()
                .filter(e -> e.relationType() == RelationType.PART_OF
                        && (e.targetId().equals(chm.id()) || e.targetId().equals(wch11Id))
                        && e.sourceId().equals(nodeByCode.get("4CH1").id()))
                .findAny()).isEmpty();
        // the V6 edge set is exactly what it was plus nothing
        List<KnowledgeEdge> v6EdgesAfter = edgeByKey.values().stream()
                .filter(e -> "seed-v6".equals(e.createdBy())).toList();
        assertThat(v6EdgesAfter).hasSize(1);
        assertThat(v6EdgeCount).isEqualTo(1);
    }
}
