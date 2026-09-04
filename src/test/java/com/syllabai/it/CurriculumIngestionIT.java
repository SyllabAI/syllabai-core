package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.CurriculumReviewService;
import com.syllabai.teacher.ingestion.CurriculumDraftDto;
import com.syllabai.teacher.ingestion.CurriculumIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (T-010): the real-corpus curriculum draft — extracted from
 * Pearson's published Edexcel IAL Chemistry 2018 specification by
 * syllabai-parser's outline extractor — lands as an all-SUGGESTED KG seed
 * under the V6 subject root, is idempotent on re-ingest, and passes through
 * the full teacher validation workflow to the ACTIVE version gate.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CurriculumIngestionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private CurriculumIngestionService ingestion;
    @Autowired
    private CurriculumReviewService review;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;

    private CurriculumDraftDto draft() throws Exception {
        String raw = Files.readString(
                Path.of("src/test/resources/fixtures/curriculum-draft-ial-chem-2018.json"));
        return JSON.readValue(raw, CurriculumDraftDto.class);
    }

    @Test
    @Order(1)
    @DisplayName("real spec draft ingests under the V6 subject root: 6/20/15, all SUGGESTED with provenance")
    void ingestsRealSpecDraft() throws Exception {
        CurriculumDraftDto draft = draft();
        CurriculumIngestionService.IngestionSummary summary =
                ingestion.ingest(draft, UUID.randomUUID());

        // the real spec structure, pinned exactly (regression = extraction drift)
        assertThat(summary.units()).isEqualTo(6);
        assertThat(summary.topics()).isEqualTo(20);
        assertThat(summary.subtopics()).isEqualTo(15);

        // V6 already seeded IAL-CHEM-2018 + subject CHM + root CHM — resolution
        // must REUSE them, never duplicate the curriculum identity
        assertThat(summary.curriculumVersionId()).isNotNull();
        assertThat(summary.subjectRootNodeId()).isEqualTo(
                java.util.UUID.fromString("20000000-0000-0000-0000-000000000001"));
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-ROOT")).isEmpty();
        assertThat(curriculumVersions.findByBoardAndQualificationAndCode(
                "Edexcel", "IAL", "IAL-CHEM-2018")).isPresent();

        // spec nodes exist with namespaced codes + provenance fingerprints
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-U1").orElseThrow().title())
                .isEqualTo("Structure, Bonding and Introduction to Organic Chemistry");
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-U1-T1").orElseThrow().description())
                .contains("p20").contains("edexcel-numbered-outline-v1");
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-U1-T3-C").orElseThrow().title())
                .isEqualTo("Shapes of molecules");
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-U6").orElseThrow().title())
                .isEqualTo("Practical Skills in Chemistry II");

        var u1 = knowledgeNodes.findByCode("IALCHEM2018-U1").orElseThrow();
        assertThat(u1.validationStatus().name()).isEqualTo("SUGGESTED");
        assertThat(u1.provenance()).contains("curriculum:").contains("checksum:");

        // review queue sees exactly the spec-derived nodes as SUGGESTED
        // (V6's manual nodes sit at UNVALIDATED, a different state)
        var queue = review.nodes(summary.curriculumVersionId(),
                com.syllabai.knowledge.KnowledgeNode.ValidationStatus.SUGGESTED);
        assertThat(queue).hasSize(41);   // 6 units + 20 topics + 15 subtopics
    }

    @Test
    @Order(2)
    @DisplayName("re-ingesting the identical draft is an idempotent no-op")
    void idempotentReingest() throws Exception {
        CurriculumDraftDto draft = draft();
        CurriculumIngestionService.IngestionSummary first = ingestion.ingest(draft, null);
        CurriculumIngestionService.IngestionSummary second = ingestion.ingest(draft, null);

        assertThat(second.subjectRootNodeId()).isEqualTo(first.subjectRootNodeId());
        assertThat(knowledgeNodes.findByCode("IALCHEM2018-U1")).isPresent();
        // queue size unchanged — no duplicated nodes
        long queueSize = review.nodes(first.curriculumVersionId(),
                com.syllabai.knowledge.KnowledgeNode.ValidationStatus.SUGGESTED).size();
        assertThat(queueSize).isEqualTo(second.units() + second.topics() + second.subtopics());
    }

    @Test
    @Order(3)
    @DisplayName("teacher validation: nodes → version gate refuses until complete, then ACTIVATES")
    void validationWorkflow() throws Exception {
        CurriculumIngestionService.IngestionSummary summary =
                ingestion.ingest(draft(), null);

        // gate refuses while SUGGESTED/UNVALIDATED nodes remain (the spec tree
        // joins V6's manual tree under the same CHM root — both must validate)
        UUID versionId = summary.curriculumVersionId();
        assertThatThrownBy(() -> review.validateVersion(versionId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unvalidated node");

        // validate the whole tree bottom-up, spec nodes first
        review.nodes(versionId, null).forEach(
                node -> review.validateNode(node.id()));
        var overview = review.validateVersion(versionId);

        assertThat(overview.status()).isEqualTo("ACTIVE");
        assertThat(overview.suggestedNodes()).isZero();
        // validated nodes include V6's manual tree + the 41 spec nodes
        assertThat(overview.validatedNodes()).isGreaterThanOrEqualTo(41);
    }
}
