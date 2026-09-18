package com.syllabai.sme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import com.syllabai.assessment.QuestionOptionRepository;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionPartRepository;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-facing SME question-bank ingestion (ADR-026): a ZIP package
 * produced by {@code scripts/s104_build_question_package.py} in
 * syllabai-resources containing {@code package.json}
 * (sme-question-package/1.0) and {@code assets/*} images.
 *
 * <p><b>Replace semantics, evidence-safe:</b> in one transaction every
 * currently-active question is deactivated (rows survive — attempts, the
 * pending marking queue, BKT evidence and FK chains are untouched) and the
 * SME set is inserted fresh: questions (PAST_PAPER provenance,
 * difficulty_source=SME), VALIDATED v1 versions, MCQ options, structured
 * parts, VALIDATED mark schemes with one mark point per part (worked-solution
 * text), secondary topic mappings, question→spec-point mappings
 * (AI_VALIDATED), and stem/solution assets.</p>
 *
 * <p>Fail-closed validation — wrong package version, duplicate external refs,
 * unresolvable topic/spec codes, MCQs without exactly one correct option,
 * part-marks mismatches, dangling or traversal-looking asset references —
 * rejects the whole package (400) and leaves the live bank untouched.</p>
 */
@Service
public class SmeQuestionIngestService {

    private static final Logger log = LoggerFactory.getLogger(SmeQuestionIngestService.class);

    static final String SUPPORTED_PACKAGE_VERSION = "1.0";
    static final String SOURCE_DOCUMENT_ID = "sme-eq-igcse-chemistry-19";
    static final String EXTRACTION_METHOD = "sme-corpus-import-v1 (ADR-026)";

    private static final Pattern SAFE_FILENAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,511}");
    private static final Pattern ASSET_REF = Pattern.compile("\\(assets/([^)\\s]+)\\)");

    /** house pattern: the app context exposes no ObjectMapper bean */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final QuestionOptionRepository questionOptions;
    private final QuestionPartRepository questionParts;
    private final MarkSchemeRepository markSchemes;
    private final MarkPointRepository markPoints;
    private final QuestionTopicRepository questionTopics;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final SmeQuestionSpecPointRepository specPoints;
    private final QuestionAssetRepository assets;

    public SmeQuestionIngestService(QuestionRepository questions,
            QuestionVersionRepository questionVersions,
            QuestionOptionRepository questionOptions,
            QuestionPartRepository questionParts,
            MarkSchemeRepository markSchemes,
            MarkPointRepository markPoints,
            QuestionTopicRepository questionTopics,
            KnowledgeNodeRepository knowledgeNodes,
            SmeQuestionSpecPointRepository specPoints,
            QuestionAssetRepository assets) {
        this.questions = questions;
        this.questionVersions = questionVersions;
        this.questionOptions = questionOptions;
        this.questionParts = questionParts;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
        this.questionTopics = questionTopics;
        this.knowledgeNodes = knowledgeNodes;
        this.specPoints = specPoints;
        this.assets = assets;
    }

    @Transactional
    public SmeQuestionPackageDtos.IngestSummary ingest(byte[] zipBytes) {
        ParsedPackage parsed = unzip(zipBytes);
        SmeQuestionPackageDtos.Package pkg = parsed.pkg();
        Map<String, byte[]> assetBytes = parsed.assetBytes();
        validate(pkg, assetBytes);

        // ── resolve KG codes once (validation guarantees presence) ─────────
        Map<String, KnowledgeNode> byCode = new HashMap<>();
        Set<String> needed = new LinkedHashSet<>();
        for (var q : pkg.questions()) {
            needed.add(q.primaryTopicCode());
            if (q.secondaryTopicCodes() != null) {
                needed.addAll(q.secondaryTopicCodes());
            }
            if (q.specPoints() != null) {
                for (var sp : q.specPoints()) {
                    needed.add(sp.code());
                }
            }
        }
        for (String code : needed) {
            byCode.put(code, knowledgeNodes.findByCode(code)
                    .orElseThrow(() -> new BadRequestException("unknown KG code: " + code)));
        }

        Instant now = Instant.now();
        int deactivated = questions.deactivateAllActive();

        int mcq = 0, structured = 0, partsN = 0, optionsN = 0, markPointsN = 0,
                spN = 0, topicN = 0;
        for (var q : pkg.questions()) {
            boolean isMcq = "MCQ_SINGLE".equals(q.questionType());
            Question question = questions.save(new Question(
                    q.externalRef(),
                    isMcq ? Question.Type.MCQ_SINGLE : Question.Type.STRUCTURED,
                    q.stem() == null ? "" : q.stem(),
                    q.marks(),
                    q.difficulty(),
                    q.expectedTimeSeconds(),
                    q.commandWord(),
                    byCode.get(q.primaryTopicCode()).id(),
                    Question.Provenance.PAST_PAPER));
            question.setDifficultySource(q.difficultySource());

            QuestionVersion version = questionVersions.save(new QuestionVersion(
                    question, 1,
                    q.stem() == null ? "" : q.stem(),
                    q.marks(), q.difficulty(), q.expectedTimeSeconds(),
                    q.commandWord(),
                    QuestionVersion.ValidationState.VALIDATED,
                    SOURCE_DOCUMENT_ID, null, EXTRACTION_METHOD));

            MarkScheme scheme = markSchemes.save(new MarkScheme(
                    version, "1", SOURCE_DOCUMENT_ID, EXTRACTION_METHOD));
            scheme.validate();

            if (isMcq) {
                mcq++;
                int order = 0;
                for (var o : q.options()) {
                    questionOptions.save(new QuestionOption(question, o.label(),
                            o.text(), o.isCorrect(), null, order++));
                    optionsN++;
                }
                markPoints.save(new MarkPoint(scheme, null, "a", 0,
                        q.solutionMd() == null ? q.stem() : q.solutionMd(),
                        q.marks(), List.of(), null));
                markPointsN++;
            } else {
                structured++;
                int order = 0;
                List<QuestionPart> partRows = new ArrayList<>();
                for (var p : q.parts()) {
                    partRows.add(questionParts.save(new QuestionPart(version, p.label(),
                            p.prompt(), p.commandWord(), p.marks(), order++)));
                    partsN++;
                }
                int mpOrder = 0;
                for (QuestionPart part : partRows) {
                    String sol = solutionOf(q, part.label());
                    markPoints.save(new MarkPoint(scheme, part, part.label(), mpOrder++,
                            sol == null ? part.prompt() : sol,
                            part.marks(), List.of(), null));
                    markPointsN++;
                }
            }

            if (q.secondaryTopicCodes() != null) {
                for (String t : q.secondaryTopicCodes()) {
                    questionTopics.save(new QuestionTopic(question, byCode.get(t).id(), false));
                    topicN++;
                }
            }
            if (q.specPoints() != null) {
                for (var sp : q.specPoints()) {
                    specPoints.save(new QuestionSpecPoint(question,
                            byCode.get(sp.code()).id(), sp.role(), sp.provenance()));
                    spN++;
                }
            }
        }

        assets.deleteAllInBatch();
        for (var e : assetBytes.entrySet()) {
            assets.save(new QuestionAsset(e.getKey(), contentTypeOf(e.getKey()),
                    e.getValue().length, e.getValue(), now));
        }

        SmeQuestionPackageDtos.IngestSummary summary = new SmeQuestionPackageDtos.IngestSummary(
                pkg.questions().size(), mcq, structured, partsN, optionsN, markPointsN,
                spN, topicN, assetBytes.size(), deactivated, pkg.corpusVersion());
        log.info("SME question bank ingested: {} questions ({} mcq / {} structured), "
                        + "{} deactivated, corpus {}",
                summary.questions(), summary.mcq(), summary.structured(),
                summary.deactivated(), summary.corpusVersion());
        return summary;
    }

    private String solutionOf(SmeQuestionPackageDtos.Question q, String label) {
        if (q.parts() == null) {
            return null;
        }
        for (var p : q.parts()) {
            if (label.equals(p.label())) {
                return p.solutionMd();
            }
        }
        return null;
    }


    /** live bank snapshot for the admin status endpoint */
    @Transactional(readOnly = true)
    public SmeQuestionAdminController.BankStatusView status() {
        long active = questions.findAllActive().size();
        long mcq = questions.findAllActive().stream()
                .filter(q -> q.type() == Question.Type.MCQ_SINGLE).count();
        long structured = active - mcq;
        return new SmeQuestionAdminController.BankStatusView(
                active, mcq, structured, specPoints.count(), assets.count());
    }

    // ── validation (fail-closed; package-visible for unit tests) ──────────

    void validate(SmeQuestionPackageDtos.Package pkg, Map<String, byte[]> assetBytes) {
        if (pkg == null || pkg.questions() == null || pkg.questions().isEmpty()) {
            throw new BadRequestException("package carries no questions");
        }
        if (!SUPPORTED_PACKAGE_VERSION.equals(pkg.packageVersion())) {
            throw new BadRequestException("unsupported package version: " + pkg.packageVersion());
        }
        Set<String> refs = new HashSet<>();
        Set<String> referencedAssets = new HashSet<>();
        for (var q : pkg.questions()) {
            if (q.externalRef() == null || q.externalRef().isBlank()
                    || q.externalRef().length() > 60 || !refs.add(q.externalRef())) {
                throw new BadRequestException("bad or duplicate externalRef: " + q.externalRef());
            }
            if (q.marks() <= 0 || q.difficulty() < 1 || q.difficulty() > 5
                    || q.expectedTimeSeconds() <= 0) {
                throw new BadRequestException("bad marks/difficulty/time on " + q.externalRef());
            }
            boolean isMcq = "MCQ_SINGLE".equals(q.questionType());
            boolean isStructured = "STRUCTURED".equals(q.questionType());
            if (!isMcq && !isStructured) {
                throw new BadRequestException("unknown questionType on " + q.externalRef());
            }
            if (q.primaryTopicCode() == null || q.primaryTopicCode().isBlank()) {
                throw new BadRequestException("missing primaryTopicCode on " + q.externalRef());
            }
            if (isMcq) {
                if (q.options() == null || q.options().size() < 2) {
                    throw new BadRequestException("MCQ needs >=2 options: " + q.externalRef());
                }
                long correct = q.options().stream()
                        .filter(SmeQuestionPackageDtos.Option::isCorrect).count();
                if (correct != 1) {
                    throw new BadRequestException(
                            "MCQ must have exactly one correct option: " + q.externalRef());
                }
                Set<String> labels = new HashSet<>();
                for (var o : q.options()) {
                    if (o.label() == null || !labels.add(o.label())) {
                        throw new BadRequestException(
                                "bad/duplicate option label: " + q.externalRef());
                    }
                }
            } else {
                if (q.parts() == null || q.parts().isEmpty()) {
                    throw new BadRequestException("STRUCTURED needs parts: " + q.externalRef());
                }
                int sum = q.parts().stream().mapToInt(SmeQuestionPackageDtos.Part::marks).sum();
                if (sum != q.marks()) {
                    throw new BadRequestException("part marks sum != question marks on "
                            + q.externalRef());
                }
                Set<String> labels = new HashSet<>();
                for (var p : q.parts()) {
                    if (p.label() == null || p.label().isBlank() || !labels.add(p.label())) {
                        throw new BadRequestException(
                                "bad/duplicate part label: " + q.externalRef());
                    }
                }
            }
            if (q.specPoints() != null) {
                for (var sp : q.specPoints()) {
                    if (sp.code() == null || sp.code().isBlank()
                            || (!"PRIMARY".equals(sp.role()) && !"SECONDARY".equals(sp.role()))) {
                        throw new BadRequestException("bad spec point on " + q.externalRef());
                    }
                }
            }
            collectRefs(q.stem(), referencedAssets);
            collectRefs(q.solutionMd(), referencedAssets);
            if (q.parts() != null) {
                for (var p : q.parts()) {
                    collectRefs(p.prompt(), referencedAssets);
                    collectRefs(p.solutionMd(), referencedAssets);
                }
            }
        }
        for (String name : referencedAssets) {
            if (!assetBytes.containsKey(name)) {
                throw new BadRequestException("referenced asset missing from package: " + name);
            }
        }
        for (String name : assetBytes.keySet()) {
            if (!SAFE_FILENAME.matcher(name).matches()) {
                throw new BadRequestException("unsafe asset filename: " + name);
            }
        }
    }

    private void collectRefs(String md, Set<String> into) {
        if (md == null) {
            return;
        }
        Matcher m = ASSET_REF.matcher(md);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private String contentTypeOf(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private record ParsedPackage(SmeQuestionPackageDtos.Package pkg,
            Map<String, byte[]> assetBytes) {
    }

    private ParsedPackage unzip(byte[] zipBytes) {
        Map<String, byte[]> assetBytes = new HashMap<>();
        String packageJson = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = zin.readAllBytes();
                String name = entry.getName();
                if ("package.json".equals(name)) {
                    packageJson = new String(data, StandardCharsets.UTF_8);
                } else if (name.startsWith("assets/")) {
                    assetBytes.put(name.substring("assets/".length()), data);
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("could not read the corpus package (not a ZIP?)");
        }
        if (packageJson == null) {
            throw new BadRequestException("package.json missing from the corpus package");
        }
        final SmeQuestionPackageDtos.Package pkg;
        try {
            pkg = JSON.readValue(packageJson, SmeQuestionPackageDtos.Package.class);
        } catch (IOException e) {
            throw new BadRequestException(
                    "package.json is not valid sme-question-package JSON");
        }
        return new ParsedPackage(pkg, assetBytes);
    }
}
