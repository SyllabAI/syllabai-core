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

    private SmeQuestionPackageDtos.Question structured(String ref) {
        return new SmeQuestionPackageDtos.Question(ref, "STRUCTURED",
                "", 4, 3, "SME", 300, "calculate",
                "4CH1-S1-e", List.of("4CH1-S1-c"),
                List.of(new SmeQuestionPackageDtos.SpecPoint("4CH1-1.25", "PRIMARY",
                        "AI_VALIDATED")),
                null, "structured-questions", "medium",
                List.of(), null,
                List.of(new SmeQuestionPackageDtos.Part("a", "Do this", 1, null, "sol a"),
                        new SmeQuestionPackageDtos.Part("b", "Then this", 3, null, "sol b")));
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
                List.of(new SmeQuestionPackageDtos.Part("a", "p", 1, null, "s"),
                        new SmeQuestionPackageDtos.Part("b", "p", 2, null, "s")));
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
        when(questions.deactivateAllActive()).thenReturn(34);
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
}
