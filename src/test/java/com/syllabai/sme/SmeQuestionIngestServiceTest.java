package com.syllabai.sme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionOptionRepository;
import com.syllabai.assessment.QuestionPartRepository;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-026 SME question-bank ingestion: package validation is fail-closed
 * (bad version, duplicate refs, MCQ without exactly-one-correct, marks
 * mismatch, dangling/traversal assets all reject); a good package deactivates
 * the live bank and inserts the full SME row set in one transaction.
 */
class SmeQuestionIngestServiceTest {

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final QuestionOptionRepository questionOptions =
            mock(QuestionOptionRepository.class);
    private final QuestionPartRepository questionParts = mock(QuestionPartRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);
    private final QuestionTopicRepository questionTopics =
            mock(QuestionTopicRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes =
            mock(KnowledgeNodeRepository.class);
    private final SmeQuestionSpecPointRepository specPoints =
            mock(SmeQuestionSpecPointRepository.class);
    private final QuestionAssetRepository assets = mock(QuestionAssetRepository.class);

    private final SmeQuestionIngestService service = new SmeQuestionIngestService(
            questions, questionVersions, questionOptions, questionParts, markSchemes,
            markPoints, questionTopics, knowledgeNodes, specPoints, assets);

    // ── fixtures ──────────────────────────────────────────────────────────

    private SmeQuestionPackageDtos.Package pkg(
            List<SmeQuestionPackageDtos.Question> qs) {
        return new SmeQuestionPackageDtos.Package("1.0", "test-corpus", "now",
                "test", Map.of(), qs);
    }

    private SmeQuestionPackageDtos.Question mcq(String ref) {
        return new SmeQuestionPackageDtos.Question(ref, "MCQ_SINGLE",
                "Which is correct?", 1, 3, "SME", 90, null,
                "4CH1-S1-c", List.of(),
                List.of(new SmeQuestionPackageDtos.SpecPoint("4CH1-1.15", "PRIMARY",
                        "AI_VALIDATED")),
                null, "multiple-choice-questions", "medium",
                List.of(new SmeQuestionPackageDtos.Option("A", "one", false),
                        new SmeQuestionPackageDtos.Option("B", "two", true)),
                "B is correct because…", List.of());
    }

    private SmeQuestionPackageDtos.Package pkg(String source,
            List<SmeQuestionPackageDtos.Question> qs) {
        return new SmeQuestionPackageDtos.Package("1.0", "test-corpus", "now",
                source, Map.of(), qs);
    }

    private SmeQuestionPackageDtos.Question structured(String ref) {
        return new SmeQuestionPackageDtos.Question(ref, "STRUCTURED",
                "", 4, 3, "SME", 300, "calculate",
                "4CH1-S1-e", List.of("4CH1-S1-c"),
                List.of(new SmeQuestionPackageDtos.SpecPoint("4CH1-1.25", "PRIMARY",
                        "AI_VALIDATED")),
                null, "structured-questions", "medium",
                List.of(), null,
                List.of(new SmeQuestionPackageDtos.Part("a", "Do this", 1, null, "sol a", null),
                        new SmeQuestionPackageDtos.Part("b", "Then this", 3, null, "sol b", null)));
    }

    /** MIXED: one option-bearing part + two plain parts — the -pN/-s shape */
    private SmeQuestionPackageDtos.Question mixed(String ref) {
        return new SmeQuestionPackageDtos.Question(ref, "STRUCTURED",
                "", 5, 3, "SME", 375, null,
                "4CH1-S1-e", List.of(),
                null, null, "mixed-questions", "medium",
                List.of(), null,
                List.of(
                        new SmeQuestionPackageDtos.Part("a", "Complete the table", 2,
                                null, "sol a", null),
                        new SmeQuestionPackageDtos.Part("b", "Draw the graph", 2,
                                null, "sol b", null),
                        new SmeQuestionPackageDtos.Part("c", "Write down the letter", 1,
                                null, "sol c",
                                List.of(new SmeQuestionPackageDtos.Option("A", "", false),
                                        new SmeQuestionPackageDtos.Option("B", "", false),
                                        new SmeQuestionPackageDtos.Option("C", "", false),
                                        new SmeQuestionPackageDtos.Option("D", "", true)))));
    }

    // ── validation gates ──────────────────────────────────────────────────

    @Test
    @DisplayName("wrong package version rejects")
    void wrongVersion() {
        var bad = new SmeQuestionPackageDtos.Package("0.9", "c", "now", "s", Map.of(),
                List.of(mcq("x")));
        assertThatThrownBy(() -> service.validate(bad, Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unsupported package version");
    }

    @Test
    @DisplayName("duplicate externalRef rejects")
    void duplicateRef() {
        assertThatThrownBy(() -> service.validate(
                pkg(List.of(mcq("dup"), mcq("dup"))), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("duplicate externalRef");
    }

    @Test
    @DisplayName("MCQ without exactly one correct option rejects")
    void mcqCorrectness() {
        var zero = new SmeQuestionPackageDtos.Question("z", "MCQ_SINGLE", "s", 1, 3,
                "SME", 90, null, "4CH1-S1-c", List.of(), List.of(), null, null, "medium",
                List.of(new SmeQuestionPackageDtos.Option("A", "one", false),
                        new SmeQuestionPackageDtos.Option("B", "two", false)),
                null, List.of());
        assertThatThrownBy(() -> service.validate(pkg(List.of(zero)), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exactly one correct");
    }

    @Test
    @DisplayName("structured part-marks sum mismatch rejects")
    void marksMismatch() {
        var bad = new SmeQuestionPackageDtos.Question("m", "STRUCTURED", "", 5, 3,
                "SME", 300, null, "4CH1-S1-e", List.of(), List.of(), null, null, "medium",
                List.of(), null,
                List.of(new SmeQuestionPackageDtos.Part("a", "p", 1, null, "s", null),
                        new SmeQuestionPackageDtos.Part("b", "p", 2, null, "s", null)));
        assertThatThrownBy(() -> service.validate(pkg(List.of(bad)), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("part marks sum");
    }

    @Test
    @DisplayName("asset referenced but not shipped rejects")
    void danglingAsset() {
        var withImg = new SmeQuestionPackageDtos.Question("img", "MCQ_SINGLE",
                "Look: ![x](assets/missing.png)", 1, 3, "SME", 90, null,
                "4CH1-S1-c", List.of(), List.of(), null, null, "medium",
                List.of(new SmeQuestionPackageDtos.Option("A", "1", false),
                        new SmeQuestionPackageDtos.Option("B", "2", true)),
                null, List.of());
        assertThatThrownBy(() -> service.validate(pkg(List.of(withImg)), Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("missing from package");
    }

    @Test
    @DisplayName("traversal-looking asset filename rejects")
    void unsafeAssetName() {
        assertThatThrownBy(() -> service.validate(pkg(List.of(mcq("ok"))),
                Map.of("../evil.png", new byte[] {1})))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unsafe asset filename");
    }

    // ── happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("good package: bank deactivated, SME rows inserted, summary exact")
    void happyPath() throws Exception {
        // minimal stub: code lookup returns a node with an id
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(34);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(specPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        byte[] zip = zip(pkg(List.of(mcq("sme-a"), structured("sme-b"))),
                Map.of("diagram.png", new byte[] {1, 2, 3}));
        var summary = service.ingest(zip);

        assertThat(summary.questions()).isEqualTo(2);
        assertThat(summary.mcq()).isEqualTo(1);
        assertThat(summary.structured()).isEqualTo(1);
        assertThat(summary.parts()).isEqualTo(2);
        assertThat(summary.options()).isEqualTo(2);
        assertThat(summary.markPoints()).isEqualTo(3);   // 1 mcq + 2 parts
        assertThat(summary.specPointMappings()).isEqualTo(2);
        assertThat(summary.topicMappings()).isEqualTo(1);
        assertThat(summary.assets()).isEqualTo(1);
        assertThat(summary.deactivated()).isEqualTo(34);
    }

    @Test
    @DisplayName("ADR-026 amendment: a mixed question emits the -pN/-s multi-row family")
    void mixedQuestionEmitsMultiRowFamily() throws Exception {
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(0);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var summary = service.ingest(zip(pkg(List.of(mixed("sme-eq-t-q28"))), Map.of()));

        assertThat(summary.questions()).isEqualTo(1);
        assertThat(summary.structured()).isEqualTo(1);
        assertThat(summary.parts()).isEqualTo(2);          // the -s row's two plain parts
        assertThat(summary.options()).isEqualTo(4);         // the -p1 row's A–D
        assertThat(summary.markPoints()).isEqualTo(3);      // 1 on -p1 + 2 on -s
        assertThat(summary.deactivated()).isEqualTo(0);
        // the family's three rows: -p1 (MCQ), -s (structured), and the base ref
        // is never a row of its own — the assembler reassembles it
        var refs = new java.util.ArrayList<String>();
        org.mockito.ArgumentCaptor<com.syllabai.assessment.Question> captor =
                org.mockito.ArgumentCaptor.forClass(com.syllabai.assessment.Question.class);
        org.mockito.Mockito.verify(questions, org.mockito.Mockito.times(2)).save(captor.capture());
        captor.getAllValues().forEach(q -> refs.add(q.externalRef()));
        org.assertj.core.api.Assertions.assertThat(refs).containsExactlyInAnyOrder(
                "sme-eq-t-q28-p1", "sme-eq-t-q28-s");
    }

    @Test
    @DisplayName("ADR-026 amendment: deactivation is slice-scoped to the package's own refs")
    void deactivationIsSliceScoped() throws Exception {
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(2);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(specPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(zip(pkg(List.of(mcq("sme-eq-t-q1"))), Map.of()));

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(questions).deactivateByRefs(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .containsExactly("sme-eq-t-q1");
    }

    @Test
    @DisplayName("ADR-026 amendment: an asset-free package leaves the asset store untouched")
    void assetFreePackagePreservesAssetStore() throws Exception {
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(0);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(zip(pkg(List.of(mcq("sme-eq-t-q1"))), Map.of()));

        org.mockito.Mockito.verify(assets, org.mockito.Mockito.never()).deleteAllInBatch();
        org.mockito.Mockito.verify(assets, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("ADR-026 amendment: the package's source field becomes the version/scheme provenance")
    void packageSourceBecomesProvenance() throws Exception {
        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(0);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(zip(pkg("sme-eq-igcse-maths-a-18-higher", List.of(mcq("sme-eq-t-q1"))),
                Map.of()));

        var versionCaptor = org.mockito.ArgumentCaptor.forClass(com.syllabai.assessment.QuestionVersion.class);
        org.mockito.Mockito.verify(questionVersions).save(versionCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(versionCaptor.getValue().sourceDocumentId())
                .isEqualTo("sme-eq-igcse-maths-a-18-higher");
    }

    private byte[] zip(SmeQuestionPackageDtos.Package pkg, Map<String, byte[]> assets)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("package.json"));
            zos.write(mapper.writeValueAsString(pkg).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (var e : assets.entrySet()) {
                zos.putNextEntry(new ZipEntry("assets/" + e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("M3: extraction runs under decompression budgets — a package "
            + "whose package.json exceeds the per-entry cap rejects fail-closed")
    void zipExtractionIsBounded() throws Exception {
        byte[] zip = zip(pkg(List.of(mcq("sme-a"))), Map.of());
        assertThatThrownBy(() -> service.unzip(zip,
                new com.syllabai.shared.ZipSafety.Limits(10, 100, 10)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("per-entry budget");
    }

    @Test
    @DisplayName("asset content types: SVG demoted to download-only (R14 — script-capable "
            + "media must never be served inline from the app origin)")
    void assetContentTypesAreAllowlisted() {
        assertThat(SmeQuestionIngestService.contentTypeOf("fig.png")).isEqualTo("image/png");
        assertThat(SmeQuestionIngestService.contentTypeOf("fig.JPEG")).isEqualTo("image/jpeg");
        assertThat(SmeQuestionIngestService.contentTypeOf("doc.pdf")).isEqualTo("application/pdf");
        assertThat(SmeQuestionIngestService.contentTypeOf("graphic.svg"))
                .isEqualTo("application/octet-stream");
        assertThat(SmeQuestionIngestService.contentTypeOf("evil.html"))
                .isEqualTo("application/octet-stream");
    }
}
