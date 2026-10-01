package com.syllabai.sme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionOptionRepository;
import com.syllabai.assessment.QuestionPartRepository;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;

/**
 * Runs the REAL ingest code path (unzip → validate → full emission loop)
 * against a REAL built layer-3 package artifact, with repository side effects
 * mocked. Skips everywhere the artifact is not present, so CI stays green;
 * on an import workstation run it as:
 *
 * <pre>
 * mvn test -Dtest=SmeQuestionPackageArtifactTest \
 *          -Dsme.package.artifact=/path/to/course.question-package.zip
 * </pre>
 *
 * The expected summary counts are read from the package's own counts block
 * and cross-checked against what the service actually emits — the artifact
 * and the validator prove each other.
 */
class SmeQuestionPackageArtifactTest {

    private SmeQuestionIngestService service;
    private QuestionRepository questions;
    private QuestionVersionRepository questionVersions;
    private QuestionOptionRepository questionOptions;
    private QuestionPartRepository questionParts;
    private MarkSchemeRepository markSchemes;
    private MarkPointRepository markPoints;
    private QuestionTopicRepository questionTopics;
    private KnowledgeNodeRepository knowledgeNodes;
    private SmeQuestionSpecPointRepository specPoints;
    private QuestionAssetRepository assets;

    @BeforeEach
    void wire() {
        questions = mock(QuestionRepository.class);
        questionVersions = mock(QuestionVersionRepository.class);
        questionOptions = mock(QuestionOptionRepository.class);
        questionParts = mock(QuestionPartRepository.class);
        markSchemes = mock(MarkSchemeRepository.class);
        markPoints = mock(MarkPointRepository.class);
        questionTopics = mock(QuestionTopicRepository.class);
        knowledgeNodes = mock(KnowledgeNodeRepository.class);
        specPoints = mock(SmeQuestionSpecPointRepository.class);
        assets = mock(QuestionAssetRepository.class);
        service = new SmeQuestionIngestService(questions, questionVersions, questionOptions,
                questionParts, markSchemes, markPoints, questionTopics, knowledgeNodes,
                specPoints, assets);
    }

    @Test
    @DisplayName("a real layer-3 package artifact passes the real ingest path, summary == counts")
    void realPackageIngests() throws Exception {
        String artifact = System.getProperty("sme.package.artifact");
        Assumptions.assumeTrue(artifact != null && !artifact.isBlank(),
                "sme.package.artifact not set — artifact test skipped");

        Path zipPath = Path.of(artifact);
        Assumptions.assumeTrue(Files.isRegularFile(zipPath), "artifact zip missing: " + artifact);

        byte[] zipBytes = Files.readAllBytes(zipPath);

        when(knowledgeNodes.findByCode(any())).thenAnswer(inv ->
                Optional.of(new KnowledgeNode(inv.getArgument(0),
                        com.syllabai.knowledge.NodeType.SUBTOPIC, "t", "d",
                        KnowledgeNode.ValidationStatus.VALIDATED, "artifact-test", null)));
        when(questions.deactivateByRefs(any())).thenReturn(0);
        when(questions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionVersions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markSchemes.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionParts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionOptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(specPoints.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmeQuestionPackageDtos.IngestSummary summary = service.ingest(zipBytes);

        // the package's own counts block states what the build emitted; the
        // ingest summary states what the service actually emitted — equal
        SmeQuestionPackageDtos.Package pkg = parsePackageJson(zipPath);
        assertThat(summary.questions()).isEqualTo(pkg.questions().size());
        assertThat(summary.questions()).isEqualTo(pkg.counts().get("questions"));
        if (pkg.counts().containsKey("parts")) {
            assertThat(summary.parts()).isEqualTo(pkg.counts().get("parts"));
        }
        if (pkg.counts().containsKey("options")) {
            assertThat(summary.options()).isEqualTo(pkg.counts().get("options"));
        }
        assertThat(summary.corpusVersion()).isEqualTo(pkg.corpusVersion());
    }


    private static SmeQuestionPackageDtos.Package parsePackageJson(Path zipPath)
            throws Exception {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var entry = zf.getEntry("package.json");
            try (var in = zf.getInputStream(entry)) {
                return new ObjectMapper().readValue(in, SmeQuestionPackageDtos.Package.class);
            }
        }
    }
}
