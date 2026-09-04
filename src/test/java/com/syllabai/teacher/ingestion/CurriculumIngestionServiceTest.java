package com.syllabai.teacher.ingestion;

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
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-010 curriculum ingestion: a real-schema draft lands as an all-SUGGESTED
 * curriculum tree with full provenance; re-ingesting the identical draft is an
 * idempotent no-op; a different draft colliding on node codes fails loudly;
 * drafts without provenance or with non-SUGGESTED state are refused.
 */
class CurriculumIngestionServiceTest {

    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);

    private final CurriculumIngestionService service = new CurriculumIngestionService(
            curriculumVersions, subjects, knowledgeNodes, knowledgeEdges);

    private final Map<String, KnowledgeNode> nodeByCode = new HashMap<>();
    private final Map<String, KnowledgeEdge> edgeBySource = new HashMap<>();
    private final AtomicReference<CurriculumVersion> savedVersion = new AtomicReference<>();
    private final AtomicReference<Subject> savedSubject = new AtomicReference<>();

    {
        when(curriculumVersions.save(any(CurriculumVersion.class))).thenAnswer(inv -> {
            CurriculumVersion v = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            savedVersion.set(v);
            return v;
        });
        when(curriculumVersions.findByBoardAndQualificationAndCode(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(savedVersion.get()));
        when(subjects.save(any(Subject.class))).thenAnswer(inv -> {
            Subject s = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            savedSubject.set(s);
            return s;
        });
        when(subjects.findByCurriculumVersionIdAndCode(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(savedSubject.get()));
        when(knowledgeNodes.save(any(KnowledgeNode.class))).thenAnswer(inv -> {
            KnowledgeNode n = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            nodeByCode.put(n.code(), n);
            return n;
        });
        when(knowledgeNodes.findByCode(any())).thenAnswer(
                inv -> Optional.ofNullable(nodeByCode.get(inv.getArgument(0, String.class))));
        when(knowledgeEdges.save(any(KnowledgeEdge.class))).thenAnswer(inv -> {
            KnowledgeEdge e = TestIds.withId(inv.getArgument(0), UUID.randomUUID());
            edgeBySource.put(e.source().code(), e);
            return e;
        });
        when(knowledgeEdges.findBySourceIdAndRelationType(any(), any())).thenAnswer(inv -> {
            KnowledgeNode source = knowledgeNodes.findById((UUID) inv.getArgument(0)).orElse(null);
            return source == null ? Optional.empty()
                    : Optional.ofNullable(edgeBySource.get(source.code()));
        });
        when(knowledgeNodes.findById(any())).thenAnswer(
                inv -> nodeByCode.values().stream()
                        .filter(n -> n.id().equals(inv.getArgument(0))).findFirst());
    }

    @Test
    @DisplayName("a schema-1.1 draft becomes an all-SUGGESTED KG tree with provenance + PART_OF edges")
    void ingestsCurriculumTree() {
        CurriculumIngestionService.IngestionSummary summary =
                service.ingest(realSpecDraft(), UUID.randomUUID());

        assertThat(summary.units()).isEqualTo(2);
        assertThat(summary.topics()).isEqualTo(3);
        assertThat(summary.subtopics()).isEqualTo(2);

        // curriculum version created as DRAFT, subject linked to its KG root
        assertThat(savedVersion.get().status()).isEqualTo(CurriculumVersion.Status.DRAFT);
        assertThat(savedVersion.get().code()).isEqualTo("IAL-CHEM-2018");
        assertThat(savedSubject.get().knowledgeNodeId()).isEqualTo(summary.subjectRootNodeId());

        // namespaced codes keep the graph collision-free
        assertThat(nodeByCode.keySet()).containsExactlyInAnyOrder(
                "IALCHEM2018-ROOT", "IALCHEM2018-U1", "IALCHEM2018-U1-T1",
                "IALCHEM2018-U1-T3", "IALCHEM2018-U1-T3-C", "IALCHEM2018-U1-T3-D",
                "IALCHEM2018-U2", "IALCHEM2018-U2-T6");

        // every node SUGGESTED with the draft fingerprint as provenance
        assertThat(nodeByCode.values())
                .allSatisfy(n -> {
                    assertThat(n.validationStatus())
                            .isEqualTo(KnowledgeNode.ValidationStatus.SUGGESTED);
                    assertThat(n.provenance()).contains("curriculum:spec-doc-1")
                            .contains("method:edexcel-numbered-outline-v1")
                            .contains("checksum:1e68fdf7");
                });

        // hierarchy: unit→root, topic→unit, subtopic→topic
        assertThat(edgeBySource.get("IALCHEM2018-U1").target().code())
                .isEqualTo("IALCHEM2018-ROOT");
        assertThat(edgeBySource.get("IALCHEM2018-U1-T3").target().code())
                .isEqualTo("IALCHEM2018-U1");
        assertThat(edgeBySource.get("IALCHEM2018-U1-T3-C").target().code())
                .isEqualTo("IALCHEM2018-U1-T3");
        assertThat(edgeBySource.values())
                .allSatisfy(e -> {
                    assertThat(e.relationType()).isEqualTo(RelationType.PART_OF);
                    assertThat(e.validationStatus())
                            .isEqualTo(KnowledgeNode.ValidationStatus.SUGGESTED);
                });

        // node description cites the spec page + method + confidence (§17)
        assertThat(nodeByCode.get("IALCHEM2018-U1").title())
                .isEqualTo("Structure, Bonding and Introduction to Organic Chemistry");
        assertThat(nodeByCode.get("IALCHEM2018-U1").description())
                .contains("p18").contains("edexcel-numbered-outline-v1").contains("0.95");
    }

    @Test
    @DisplayName("re-ingesting the identical draft is an idempotent no-op (same fingerprint)")
    void idempotentReingest() {
        CurriculumDraftDto draft = realSpecDraft();
        service.ingest(draft, UUID.randomUUID());
        int nodesAfterFirst = nodeByCode.size();
        int edgesAfterFirst = edgeBySource.size();

        CurriculumIngestionService.IngestionSummary second = service.ingest(draft, UUID.randomUUID());

        assertThat(nodeByCode).hasSize(nodesAfterFirst);
        assertThat(edgeBySource).hasSize(edgesAfterFirst);
        assertThat(second.units()).isEqualTo(2);
        assertThat(second.topics()).isEqualTo(3);
        assertThat(second.subtopics()).isEqualTo(2);
    }

    @Test
    @DisplayName("same node codes with different provenance fail loudly (never silent re-seed)")
    void conflictingProvenanceFails() {
        service.ingest(realSpecDraft(), UUID.randomUUID());

        CurriculumDraftDto otherSource = new CurriculumDraftDto(
                realSpecDraft().schemaVersion(), realSpecDraft().board(),
                realSpecDraft().qualification(), realSpecDraft().code(),
                realSpecDraft().title(), realSpecDraft().subject(), realSpecDraft().units(),
                new CurriculumDraftDto.DraftProvenance("spec-doc-OTHER",
                        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        "opendataloader", "2.5.7", "edexcel-numbered-outline-v1", "SUGGESTED"));

        assertThatThrownBy(() -> service.ingest(otherSource, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists with different provenance");
    }

    @Test
    @DisplayName("drafts without provenance or not-SUGGESTED are refused")
    void refusesUnprovenancedDrafts() {
        CurriculumDraftDto noProvenance = new CurriculumDraftDto("1.1", "Edexcel", "IAL",
                "X-1", "X", new CurriculumDraftDto.SubjectDraft("CH", "Chemistry"),
                List.of(new CurriculumDraftDto.UnitDraft("U1", "Unit 1", List.of(),
                        "s1", List.of(), 1, 0.95)),
                null);
        assertThatThrownBy(() -> service.ingest(noProvenance, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("provenance");

        CurriculumDraftDto notSuggested = new CurriculumDraftDto("1.1", "Edexcel", "IAL",
                "X-1", "X", new CurriculumDraftDto.SubjectDraft("CH", "Chemistry"),
                List.of(new CurriculumDraftDto.UnitDraft("U1", "Unit 1", List.of(),
                        "s1", List.of(), 1, 0.95)),
                new CurriculumDraftDto.DraftProvenance("d", "c", "e", "v", "m", "VALIDATED"));
        assertThatThrownBy(() -> service.ingest(notSuggested, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SUGGESTED");
    }

    @Test
    @DisplayName("long draft codes are capped to the 40-char column deterministically")
    void longCodesBounded() {
        CurriculumDraftDto longCode = new CurriculumDraftDto("1.1", "Edexcel", "IAL",
                "A".repeat(60), "T", new CurriculumDraftDto.SubjectDraft("CH", "Chemistry"),
                List.of(new CurriculumDraftDto.UnitDraft("U1", "Unit 1", List.of(),
                        "s1", List.of(), 1, 0.95)),
                provenance());

        service.ingest(longCode, UUID.randomUUID());
        assertThat(nodeByCode.keySet()).allSatisfy(code -> assertThat(code.length()).isLessThanOrEqualTo(40));
    }

    private static CurriculumDraftDto realSpecDraft() {
        // shape copied from the committed real-spec fixture (subset)
        CurriculumDraftDto.TopicDraft subC = new CurriculumDraftDto.TopicDraft(
                "U1-T3-C", "Shapes of molecules", List.of(), "s050",
                List.of("e000200"), 25, 0.85);
        CurriculumDraftDto.TopicDraft subD = new CurriculumDraftDto.TopicDraft(
                "U1-T3-D", "Metallic bonding", List.of(), "s051",
                List.of(), 25, 0.85);
        CurriculumDraftDto.TopicDraft topic3 = new CurriculumDraftDto.TopicDraft(
                "U1-T3", "Bonding and Structure", List.of(subC, subD), "s049",
                List.of("e000189", "e000190"), 24, 0.95);
        CurriculumDraftDto.UnitDraft u1 = new CurriculumDraftDto.UnitDraft(
                "U1", "Structure, Bonding and Introduction to Organic Chemistry",
                List.of(new CurriculumDraftDto.TopicDraft("U1-T1",
                        "Formulae, Equations and Amount of Substance", List.of(), "s046",
                        List.of(), 20, 0.95), topic3),
                "s043", List.of(), 18, 0.95);
        CurriculumDraftDto.UnitDraft u2 = new CurriculumDraftDto.UnitDraft(
                "U2", "Energetics, Group Chemistry, Halogenoalkanes and Alcohols",
                List.of(new CurriculumDraftDto.TopicDraft("U2-T6", "Energetics",
                        List.of(), "s060", List.of(), 32, 0.95)),
                "s057", List.of(), 29, 0.95);
        return new CurriculumDraftDto("1.1", "Edexcel", "IAL", "IAL-CHEM-2018",
                "Edexcel International Advanced Level Chemistry",
                new CurriculumDraftDto.SubjectDraft("CH", "Chemistry"),
                List.of(u1, u2), provenance());
    }

    private static CurriculumDraftDto.DraftProvenance provenance() {
        return new CurriculumDraftDto.DraftProvenance("spec-doc-1",
                "1e68fdf7d854f7a61e68fdf7d854f7a61e68fdf7d854f7a61e68fdf7d854f7a6",
                "opendataloader", "2.5.7", "edexcel-numbered-outline-v1", "SUGGESTED");
    }
}
