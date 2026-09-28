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
import com.syllabai.shared.ZipSafety;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-facing SME question-bank ingestion (ADR-026, amended for the
 * multi-subject layer-3 imports): a ZIP package produced by
 * {@code scripts/s104_build_question_package.py} in syllabai-resources
 * containing {@code package.json} (sme-question-package/1.0) and
 * {@code assets/*} images.
 *
 * <p><b>Replace semantics, evidence-safe (ADR-026 amendment, layer-3
 * subject-#2 import):</b> in one transaction every currently-ACTIVE question
 * whose external ref the package re-emitates is deactivated (rows survive —
 * attempts, the pending marking queue, BKT evidence and FK chains are
 * untouched) and the package is inserted fresh: questions (PAST_PAPER
 * provenance, difficulty_source=SME), VALIDATED v1 versions, MCQ options,
 * structured parts, VALIDATED mark schemes with one mark point per part
 * (worked-solution text), secondary topic mappings, question→spec-point
 * mappings (AI_VALIDATED), and stem/solution assets. The deactivation is
 * scoped to the package's own refs, so importing subject #2 leaves the
 * serving 4CH1 pilot bank — and its real learner evidence — untouched;
 * re-importing a corpus replaces exactly that corpus. Packages that ship no
 * assets leave the asset store untouched (the store has no corpus key, so an
 * asset-bearing package still replaces the whole store — ship one package
 * per ingest when assets matter).</p>
 *
 * <p><b>Mixed questions:</b> a STRUCTURED package question whose parts carry
 * options (the {@code Part.options} amendment) is emitted in the production
 * -pN/-s multi-row family shape the {@link QuestionFamilyAssembler}
 * reassembles: one MCQ row per option-bearing part ({@code base-pK}, the
 * part's prompt as stem, its options), plus one structured row
 * ({@code base-s}) for the plain parts. Pure questions keep the single-row
 * shape. Every row of a family carries the same topic tags, difficulty and
 * expected time, so topic-scoped serving always sees the whole family.</p>
 *
 * <p><b>Provenance:</b> the package's {@code source} field (when non-blank)
 * is recorded as the source document id on every version and mark scheme the
 * package produces; the chemistry-era constant remains only as the fallback
 * for packages that omit it.</p>
 *
 * <p>Fail-closed validation — wrong package version, duplicate external refs
 * (including derived -pN/-s member refs), unresolvable topic/spec codes, MCQs
 * without exactly one correct option, part-marks mismatches, dangling or
 * traversal-looking asset references — rejects the whole package (400) and
 * leaves the live bank untouched.</p>
 */
@Service
public class SmeQuestionIngestService {

    private static final Logger log = LoggerFactory.getLogger(SmeQuestionIngestService.class);

    static final String SUPPORTED_PACKAGE_VERSION = "1.0";
    /**
     * Fallback source document id for packages that omit {@code source} —
     * the chemistry-era constant. Subject packages carry their own (e.g.
     * {@code sme-eq-igcse-maths-a-18-higher}); recording the chemistry id on
     * a maths row would be fabricated provenance.
     */
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
        String sourceDocId = sourceDocIdOf(pkg);

        // slice-scoped replace (ADR-026 amendment): deactivate exactly the
        // active rows this package re-emits — subject #2's import never
        // touches the serving 4CH1 pilot bank
        List<String> emittedRefs = new ArrayList<>();
        for (var q : pkg.questions()) {
            emittedRefs.addAll(emittedRowRefs(q));
        }
        int deactivated = questions.deactivateByRefs(emittedRefs);

        int mcq = 0, structured = 0, partsN = 0, optionsN = 0, markPointsN = 0,
                spN = 0, topicN = 0;
        for (var q : pkg.questions()) {
            boolean isMcq = "MCQ_SINGLE".equals(q.questionType());
            Question primaryRow = null;   // first emitted row of this family
            if (isMcq) {
                mcq++;
                primaryRow = saveCorpusRow(questions, sourceDocId,
                        q.externalRef(), Question.Type.MCQ_SINGLE,
                        q.stem() == null ? "" : q.stem(), q.marks(), q,
                        byCode.get(q.primaryTopicCode()).id());
                Question question = primaryRow;
                saveVersionAndScheme(questionVersions, markSchemes, markPoints,
                        question, sourceDocId, q.stem() == null ? "" : q.stem(),
                        q.marks(), q.difficulty(), q.expectedTimeSeconds(),
                        q.commandWord(),
                        q.solutionMd() == null ? q.stem() : q.solutionMd(), q.marks());
                markPointsN++;
                int order = 0;
                for (var o : q.options()) {
                    questionOptions.save(new QuestionOption(question, o.label(),
                            o.text(), o.isCorrect(), null, order++));
                    optionsN++;
                }
                saveTopicRows(questionTopics, q, byCode, question);
                topicN += q.secondaryTopicCodes() == null ? 0 : q.secondaryTopicCodes().size();
            } else {
                List<SmeQuestionPackageDtos.Part> optionParts = new ArrayList<>();
                List<SmeQuestionPackageDtos.Part> plainParts = new ArrayList<>();
                for (var p : q.parts()) {
                    if (p.options() != null && !p.options().isEmpty()) {
                        optionParts.add(p);
                    } else {
                        plainParts.add(p);
                    }
                }
                if (optionParts.isEmpty()) {
                    // pure structured question — the original single-row shape
                    structured++;
                    primaryRow = saveCorpusRow(questions, sourceDocId,
                            q.externalRef(), Question.Type.STRUCTURED,
                            q.stem() == null ? "" : q.stem(), q.marks(), q,
                            byCode.get(q.primaryTopicCode()).id());
                    Question question = primaryRow;
                    partsN += saveStructuredBody(questionVersions, markSchemes,
                            markPoints, questionParts, question, sourceDocId, q,
                            q.parts());
                    markPointsN += q.parts().size();
                    saveTopicRows(questionTopics, q, byCode, question);
                    topicN += q.secondaryTopicCodes() == null ? 0 : q.secondaryTopicCodes().size();
                } else {
                    // MIXED question — the production -pN/-s multi-row family
                    // shape (QuestionFamilyAssembler reassembles the base ref)
                    structured++;
                    int pIdx = 0;
                    for (var p : optionParts) {
                        pIdx++;
                        Question mcqRow = saveCorpusRow(questions, sourceDocId,
                                q.externalRef() + "-p" + pIdx, Question.Type.MCQ_SINGLE,
                                p.prompt(), p.marks(), q,
                                byCode.get(q.primaryTopicCode()).id());
                        if (primaryRow == null) {
                            primaryRow = mcqRow;
                        }
                        saveVersionAndScheme(questionVersions, markSchemes, markPoints,
                                mcqRow, sourceDocId, p.prompt(), p.marks(),
                                q.difficulty(), q.expectedTimeSeconds(), p.commandWord(),
                                p.solutionMd() == null ? p.prompt() : p.solutionMd(),
                                p.marks());
                        markPointsN++;
                        int order = 0;
                        for (var o : p.options()) {
                            questionOptions.save(new QuestionOption(mcqRow, o.label(),
                                    o.text(), o.isCorrect(), null, order++));
                            optionsN++;
                        }
                        saveTopicRows(questionTopics, q, byCode, mcqRow);
                        topicN += q.secondaryTopicCodes() == null ? 0 : q.secondaryTopicCodes().size();
                    }
                    if (!plainParts.isEmpty()) {
                        Question structuredRow = saveCorpusRow(questions, sourceDocId,
                                q.externalRef() + "-s", Question.Type.STRUCTURED,
                                q.stem() == null ? "" : q.stem(),
                                plainParts.stream().mapToInt(SmeQuestionPackageDtos.Part::marks).sum(),
                                q, byCode.get(q.primaryTopicCode()).id());
                        partsN += saveStructuredBody(questionVersions, markSchemes,
                                markPoints, questionParts, structuredRow, sourceDocId,
                                q, plainParts);
                        markPointsN += plainParts.size();
                        saveTopicRows(questionTopics, q, byCode, structuredRow);
                        topicN += q.secondaryTopicCodes() == null ? 0 : q.secondaryTopicCodes().size();
                    }
                }
            }
            if (q.specPoints() != null && primaryRow != null) {
                for (var sp : q.specPoints()) {
                    specPoints.save(new QuestionSpecPoint(primaryRow,
                            byCode.get(sp.code()).id(), sp.role(), sp.provenance()));
                    spN++;
                }
            }
        }

        // the asset store has no corpus key — an asset-bearing package still
        // replaces it wholesale (ADR-026), but a package that ships no assets
        // (the layer-3 maths package: stems reference the resources repo's
        // canonical image URLs) must leave the serving store untouched
        if (!assetBytes.isEmpty()) {
            assets.deleteAllInBatch();
            for (var e : assetBytes.entrySet()) {
                assets.save(new QuestionAsset(e.getKey(), contentTypeOf(e.getKey()),
                        e.getValue().length, e.getValue(), now));
            }
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

    // ── ADR-026 amendment helpers (layer-3 multi-subject imports) ─────────

    /** the package's own source document id, chemistry constant as fallback */
    private static String sourceDocIdOf(SmeQuestionPackageDtos.Package pkg) {
        return pkg.source() == null || pkg.source().isBlank()
                ? SOURCE_DOCUMENT_ID : pkg.source();
    }

    /**
     * Every external ref the ingest will EMIT for one package question — the
     * base ref, plus the derived {@code -pK} MCQ member refs and the
     * {@code -s} structured member ref for mixed questions. Drives both the
     * slice-scoped deactivation and the extended ref-uniqueness validation.
     */
    static List<String> emittedRowRefs(SmeQuestionPackageDtos.Question q) {
        List<String> refs = new ArrayList<>();
        refs.add(q.externalRef());
        if ("STRUCTURED".equals(q.questionType()) && q.parts() != null) {
            int pIdx = 0;
            boolean anyOptionPart = false;
            boolean anyPlainPart = false;
            for (var p : q.parts()) {
                if (p.options() != null && !p.options().isEmpty()) {
                    anyOptionPart = true;
                    pIdx++;
                    refs.add(q.externalRef() + "-p" + pIdx);
                } else {
                    anyPlainPart = true;
                }
            }
            if (anyOptionPart && anyPlainPart) {
                refs.add(q.externalRef() + "-s");
            }
        }
        return refs;
    }

    /** one Question row: PAST_PAPER provenance, SME difficulty source */
    private static Question saveCorpusRow(QuestionRepository questions,
            String sourceDocId, String ref, Question.Type type, String stem,
            int marks, SmeQuestionPackageDtos.Question q, UUID primaryTopicNodeId) {
        Question question = questions.save(new Question(
                ref, type, stem, marks, q.difficulty(), q.expectedTimeSeconds(),
                q.commandWord(), primaryTopicNodeId, Question.Provenance.PAST_PAPER));
        question.setDifficultySource(q.difficultySource());
        return question;
    }

    /** the VALIDATED v1 version + validated mark scheme every MCQ row carries; 1 mark point */
    private static void saveVersionAndScheme(
            QuestionVersionRepository questionVersions, MarkSchemeRepository markSchemes,
            MarkPointRepository markPoints, Question question, String sourceDocId,
            String stem, int marks, int difficulty, int expectedTimeSeconds,
            String commandWord, String solutionMd, int markPointMarks) {
        QuestionVersion version = questionVersions.save(new QuestionVersion(
                question, 1, stem, marks, difficulty, expectedTimeSeconds,
                commandWord, QuestionVersion.ValidationState.VALIDATED,
                sourceDocId, null, EXTRACTION_METHOD));
        MarkScheme scheme = markSchemes.save(new MarkScheme(
                version, "1", sourceDocId, EXTRACTION_METHOD));
        scheme.validate();
        markPoints.save(new MarkPoint(scheme, null, "a", 0,
                solutionMd == null ? stem : solutionMd,
                markPointMarks, List.of(), null));
    }

    /** structured body: part rows in order + one mark point per part; returns the part count */
    private int saveStructuredBody(QuestionVersionRepository questionVersions,
            MarkSchemeRepository markSchemes, MarkPointRepository markPoints,
            QuestionPartRepository questionParts, Question question, String sourceDocId,
            SmeQuestionPackageDtos.Question q,
            List<SmeQuestionPackageDtos.Part> parts) {
        QuestionVersion version = questionVersions.save(new QuestionVersion(
                question, 1,
                q.stem() == null ? "" : q.stem(),
                parts.stream().mapToInt(SmeQuestionPackageDtos.Part::marks).sum(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                QuestionVersion.ValidationState.VALIDATED,
                sourceDocId, null, EXTRACTION_METHOD));
        MarkScheme scheme = markSchemes.save(new MarkScheme(
                version, "1", sourceDocId, EXTRACTION_METHOD));
        scheme.validate();
        int order = 0;
        List<QuestionPart> partRows = new ArrayList<>();
        for (var p : parts) {
            partRows.add(questionParts.save(new QuestionPart(version, p.label(),
                    p.prompt(), p.commandWord(), p.marks(), order++)));
        }
        int mpOrder = 0;
        for (QuestionPart part : partRows) {
            String sol = solutionOf(q, part.label());
            markPoints.save(new MarkPoint(scheme, part, part.label(), mpOrder++,
                    sol == null ? part.prompt() : sol,
                    part.marks(), List.of(), null));
        }
        return partRows.size();
    }

    /** secondary topic mappings — every row of a family carries the SAME tags */
    private static void saveTopicRows(QuestionTopicRepository questionTopics,
            SmeQuestionPackageDtos.Question q, Map<String, KnowledgeNode> byCode,
            Question row) {
        if (q.secondaryTopicCodes() == null) {
            return;
        }
        for (String t : q.secondaryTopicCodes()) {
            questionTopics.save(new QuestionTopic(row, byCode.get(t).id(), false));
        }
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
                    || q.externalRef().length() > 80 || !refs.add(q.externalRef())) {
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
                    // ADR-026 amendment: option-bearing parts inside a
                    // STRUCTURED question make it MIXED — each obeys the MCQ
                    // option rules (the ingest emits it as a -pK MCQ row)
                    if (p.options() != null) {
                        if (p.options().size() < 2) {
                            throw new BadRequestException("option part needs >=2 options: "
                                    + q.externalRef() + " part " + p.label());
                        }
                        long correct = p.options().stream()
                                .filter(SmeQuestionPackageDtos.Option::isCorrect).count();
                        if (correct != 1) {
                            throw new BadRequestException(
                                    "option part must have exactly one correct option: "
                                            + q.externalRef() + " part " + p.label());
                        }
                        Set<String> optionLabels = new HashSet<>();
                        for (var o : p.options()) {
                            if (o.label() == null || !optionLabels.add(o.label())) {
                                throw new BadRequestException(
                                        "bad/duplicate option label on "
                                                + q.externalRef() + " part " + p.label());
                            }
                        }
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
            // derived -pN/-s member refs join the uniqueness set — a package
            // that literally contains the ref a mixed emission would derive
            // must not pass validation (the DB unique constraint would only
            // fire after partial work inside the transaction). The base ref
            // (index 0) is already validated above.
            List<String> emitted = emittedRowRefs(q);
            for (int i = 1; i < emitted.size(); i++) {
                if (!refs.add(emitted.get(i))) {
                    throw new BadRequestException("duplicate externalRef: " + emitted.get(i));
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

    /** Extension → served media type (R14). SVG is deliberately DEMOTED to
     *  application/octet-stream: it would otherwise be served inline from the
     *  app origin (script-capable media type = stored-XSS shape on a
     *  learner-facing path; ingestion is admin-gated, serving is defense in
     *  depth). Raster images + PDF are the legitimate inline set. */
    static String contentTypeOf(String filename) {
        String f = filename.toLowerCase();
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private record ParsedPackage(SmeQuestionPackageDtos.Package pkg,
            Map<String, byte[]> assetBytes) {
    }

    private ParsedPackage unzip(byte[] zipBytes) {
        return unzip(zipBytes, ZipSafety.Limits.defaults());
    }

    // deep-audit 09-28 M3: extraction runs under absolute decompression budgets
    // (per-entry / total / entry-count) via the shared bounded walker — the
    // compressed multipart cap bounds nothing, compression ratio is attacker-
    // chosen and readAllBytes() used to allocate the full uncompressed entry
    // before any check could run. The test-visible overload pins the caps.
    ParsedPackage unzip(byte[] zipBytes, ZipSafety.Limits limits) {
        Map<String, byte[]> assetBytes = new HashMap<>();
        String[] packageJson = new String[1];
        try {
            ZipSafety.readEach(zipBytes, limits, (name, data) -> {
                if ("package.json".equals(name)) {
                    packageJson[0] = new String(data, StandardCharsets.UTF_8);
                } else if (name.startsWith("assets/")) {
                    assetBytes.put(name.substring("assets/".length()), data);
                }
            });
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new BadRequestException("could not read the corpus package (not a ZIP?)");
        }
        if (packageJson[0] == null) {
            throw new BadRequestException("package.json missing from the corpus package");
        }
        final SmeQuestionPackageDtos.Package pkg;
        try {
            pkg = JSON.readValue(packageJson[0], SmeQuestionPackageDtos.Package.class);
        } catch (IOException e) {
            throw new BadRequestException(
                    "package.json is not valid sme-question-package JSON");
        }
        return new ParsedPackage(pkg, assetBytes);
    }
}
