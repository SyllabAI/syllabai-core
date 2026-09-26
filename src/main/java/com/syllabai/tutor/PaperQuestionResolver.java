package com.syllabai.tutor;

import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.FetchService;
import com.syllabai.content.FetchService.FetchResult;
import com.syllabai.content.FetchService.FetchPaperHit;
import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.curriculum.CurriculumScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deterministic paper-question resolver for the tutor serving path (T-024
 * KA-RAG). When a learner asks <em>"explain question 10 from june 2019 paper
 * 2"</em> or <em>"give me the answer of jan 2022 question 4 paper 1"</em>,
 * pure vector similarity dilutes the session/paper/number constraints across
 * the whole corpus. This resolver binds them deterministically and produces
 * <strong>lead evidence</strong> that {@link KaRagService} pins at the head of
 * the pool — outside RRF (plan §7 lead-item pattern, the same posture the CLA
 * PAST_PAPER_QUESTION path uses for stems). Zero vector calls on the happy
 * path: resolution is metadata + document rows only.
 *
 * <p>Two tiers, first match wins:</p>
 * <ol>
 *   <li><strong>Bank anchor</strong> — {@link FetchService} (R4 plan §7 FETCH)
 *       resolves a single paper within the ask's curriculum scope. Served only
 *       when the paper row is VALIDATED — exactly branch 1 of the serving law
 *       ({@code ChunkVectorRepository#searchServingEligible}): paper-anchored
 *       QP/MS chunks gate on the <em>exam_papers</em> row, and the linked
 *       document rows may still sit at SUGGESTED in the documents table (they
 *       do in production — the 09-26 live probes proved a paper-VALIDATED +
 *       document-SUGGESTED doc serves through the vector arm). A REJECTED
 *       document row never serves. Evidence is the question-number-matching
 *       chunks of those documents (QP stem chunks, MS answer chunks when the
 *       ask is mark-scheme-seeking).</li>
 *   <li><strong>Question-card anchor</strong> — the per-session card document
 *       {@code qcard-{code}-{unit}-{SERIES}-{year}.txt} (uniquely named per
 *       session, covering sessions the structured bank does not). Served only
 *       when the card document is VALIDATED; the question's card chunk is
 *       selected by the chunk's {@code atomNumber} column (zero-padding and
 *       {@code q}-prefix tolerant) or, when unpopulated, by question-number
 *       markers in the card text.</li>
 * </ol>
 *
 * <p>Serving law (T-C20) holds on every tier: REJECTED never serves; the
 * paper/document validation gate mirrors
 * {@code ChunkVectorRepository#searchServingEligible}. Ambiguity is honest:
 * when a paper identity cannot be bound (no explicit unit, several candidate
 * cards), the resolver returns empty and the unmodified vector+KG path serves
 * the ask exactly as before — the resolver never guesses, never widens.</p>
 *
 * <p>"paper 1 / paper 2" phrasing maps to the home-unit candidates
 * {@code 1C}/{@code 2C} with the regional variants as deterministic
 * tiebreak (R last, first match wins); an explicit unit ("2CR") or full code
 * ("4CH1/2C") binds exactly through the shared
 * {@link FetchQueryParser}.</p>
 */
@Service
public class PaperQuestionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PaperQuestionResolver.class);

    /** Pinned evidence per tier — lead items must leave room for KG+vector evidence. */
    private static final int MAX_QP_ITEMS = 2;
    private static final int MAX_MS_ITEMS = 1;
    private static final int MAX_CARD_ITEMS = 2;

    /** "paper 1" / "paper 2" (optionally "paper number 1") — the parser does not bind this phrasing. */
    private static final Pattern PAPER_HINT = Pattern.compile("\\bpaper\\s*(?:number\\s*)?([12])\\b",
            Pattern.CASE_INSENSITIVE);

    private final FetchService fetchService;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public PaperQuestionResolver(FetchService fetchService, DocumentRepository documents,
                                 DocumentChunkRepository chunks) {
        this.fetchService = fetchService;
        this.documents = documents;
        this.chunks = chunks;
    }

    /**
     * Resolve the ask's paper-question identity into pinned lead evidence.
     * Empty result is the honest outcome — never a guess, never a widening.
     */
    public List<EvidenceItem> resolve(String query, CurriculumScope scope) {
        if (query == null || query.isBlank() || scope == null) {
            return List.of();
        }
        FetchResult fetch;
        try {
            fetch = fetchService.fetch(query, scope);
        } catch (RuntimeException e) {
            // the resolver must never break the ask: the vector+KG path remains the safety net
            LOG.warn("paper-question resolver: fetch step failed ({}); falling back to card anchor",
                    e.getClass().getSimpleName());
            fetch = null;
        }

        try {
            if (fetch != null) {
                List<EvidenceItem> bankAnchored = bankAnchored(fetch);
                if (!bankAnchored.isEmpty()) {
                    return bankAnchored;
                }
            }
            return cardAnchored(query, fetch == null ? null : fetch.parsed(), scope);
        } catch (RuntimeException e) {
            LOG.warn("paper-question resolver: resolution failed ({}); serving the unmodified path",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ── tier 1: bank anchor ─────────────────────────────────────────────────

    private List<EvidenceItem> bankAnchored(FetchResult fetch) {
        ParsedFetchQuery parsed = fetch.parsed();
        if (parsed.qnum() == null || fetch.ambiguous() || fetch.parseDefect()
                || fetch.papers().size() != 1) {
            return List.of();
        }
        FetchPaperHit paper = fetch.papers().get(0);
        if (!"VALIDATED".equals(paper.validationState())) {
            return List.of();   // serving law: SUGGESTED bank rows never serve to learners
        }
        List<EvidenceItem> items = new ArrayList<>();
        if (paper.qpDocumentId() != null) {
            items.addAll(documentQuestionChunks(paper.qpDocumentId(), parsed.qnum(),
                    Document.Kind.QUESTION_PAPER, MAX_QP_ITEMS));
        }
        if (parsed.msSeeking() && paper.msDocumentId() != null) {
            items.addAll(documentQuestionChunks(paper.msDocumentId(), parsed.qnum(),
                    Document.Kind.MARK_SCHEME, MAX_MS_ITEMS));
        }
        return List.copyOf(items);
    }

    private List<EvidenceItem> documentQuestionChunks(String canonicalDocumentId, int qnum,
                                                      Document.Kind expectedKind, int cap) {
        if (canonicalDocumentId == null) {
            return List.of();
        }
        // Serving law branch 1 (paper-anchored): the paper row's VALIDATED state
        // was already checked by the caller — the document row's own SUGGESTED
        // state must NOT block (production: every documents row is SUGGESTED
        // while 11 exam_papers rows are VALIDATED, and the vector arm serves
        // exactly those docs). Only a REJECTED document row is excluded here.
        Optional<Document> doc = documents.findTopByDocumentIdOrderByDocVersionDesc(canonicalDocumentId)
                .filter(d -> !"REJECTED".equals(d.validationState()));
        if (doc.isEmpty() || doc.get().kind() != expectedKind) {
            return List.of();
        }
        List<DocumentChunk> rows = chunks.findByDocumentRowIdOrderByChunkIndexAsc(doc.get().id());
        return matchQuestion(rows, qnum).stream()
                .limit(cap)
                .map(chunk -> evidence(doc.get(), chunk))
                .toList();
    }

    // ── tier 2: question-card anchor ────────────────────────────────────────

    private List<EvidenceItem> cardAnchored(String query, ParsedFetchQuery parsed,
                                            CurriculumScope scope) {
        if (parsed == null || parsed.qnum() == null || parsed.year() == null
                || parsed.series() == null || parsed.series().isBlank()) {
            return List.of();   // not a bindable paper-style ask
        }
        String subjectCode = subjectCode(scope);
        List<String> unitCandidates = unitCandidates(parsed, query);
        if (subjectCode == null || unitCandidates.isEmpty()) {
            return List.of();
        }
        for (String code : List.of(subjectCode, "4CH0")) {   // 4CH0 = legacy alias, second
            for (String unit : unitCandidates) {
                String fileName = "qcard-" + code + "-" + unit + "-"
                        + parsed.series() + "-" + parsed.year() + ".txt";
                Optional<Document> card = documents.findTopByFileNameOrderByDocVersionDesc(fileName)
                        .filter(this::servesUnderServingLaw);
                if (card.isEmpty()) {
                    continue;
                }
                List<DocumentChunk> rows = chunks.findByDocumentRowIdOrderByChunkIndexAsc(card.get().id());
                List<DocumentChunk> matched = matchQuestion(rows, parsed.qnum());
                if (!matched.isEmpty()) {
                    return matched.stream()
                            .limit(MAX_CARD_ITEMS)
                            .map(chunk -> evidence(card.get(), chunk))
                            .toList();
                }
            }
        }
        return List.of();
    }

    /** Scope code "4CH1-2017" → subject code "4CH1" (the card file-name prefix). */
    private static String subjectCode(CurriculumScope scope) {
        String code = scope.code();
        if (code == null || code.isBlank()) {
            return null;
        }
        int dash = code.indexOf('-');
        String subject = dash < 0 ? code : code.substring(0, dash);
        return subject.isBlank() ? null : subject;
    }

    /**
     * Unit binding, deterministic: explicit full code → exact unit; explicit
     * bare unit → exact unit; "paper N" phrasing → home unit then regional
     * variant; nothing → both home units then both regional variants (only a
     * single surviving card with a question match is pinned — first match
     * wins by construction, so a corpus holding exactly one session paper
     * resolves and a corpus holding several stays honest via the first).
     */
    private static List<String> unitCandidates(ParsedFetchQuery parsed, String query) {
        if (parsed.paperCode() != null) {
            String unit = unitFromPaperCode(parsed.paperCode());
            if (unit != null) {
                return List.of(unit);
            }
        }
        if (parsed.unit() != null && !parsed.unit().isBlank()) {
            return List.of(parsed.unit().strip().toUpperCase());
        }
        Matcher m = PAPER_HINT.matcher(query == null ? "" : query);
        if (m.find()) {
            return m.group(1).equals("1") ? List.of("1C", "1CR") : List.of("2C", "2CR");
        }
        return List.of("1C", "2C", "1CR", "2CR");
    }

    /** "4CH1/2C" or "4CH0-1CR" → "2C" / "1CR" (separator and spaces tolerant). */
    private static String unitFromPaperCode(String paperCode) {
        int cut = Math.max(paperCode.lastIndexOf('/'), paperCode.lastIndexOf('-'));
        String unit = (cut < 0 ? paperCode : paperCode.substring(cut + 1))
                .replace(" ", "").toUpperCase();
        return unit.matches("[12]CR?") ? unit : null;
    }

    // ── question-chunk selection ────────────────────────────────────────────

    /**
     * The chunks of a per-question document that carry the asked question
     * number: the {@code atomNumber} column first (authoritative when
     * populated), question-number markers in the text as the fallback. Never
     * more than a few rows — the pool must keep room for the retrieval arms.
     */
    private static List<DocumentChunk> matchQuestion(List<DocumentChunk> rows, int qnum) {
        List<DocumentChunk> byAtom = rows.stream()
                .filter(c -> atomMatches(c.atomNumber(), qnum))
                .toList();
        if (!byAtom.isEmpty()) {
            return byAtom.size() <= 3 ? byAtom : byAtom.subList(0, 3);
        }
        return rows.stream()
                .filter(c -> contentMarks(c.content(), qnum))
                .limit(3)
                .toList();
    }

    /** "q10", "10-a", "010" all normalize to 10; null/other questions never match. */
    private static boolean atomMatches(String atomNumber, int qnum) {
        if (atomNumber == null) {
            return false;
        }
        Matcher m = Pattern.compile("q?\\s*0*(\\d+)").matcher(atomNumber.strip().toLowerCase());
        return m.find() && Integer.parseInt(m.group(1)) == qnum;
    }

    /**
     * Text markers that a chunk is about question N:
     * <ol>
     *   <li>{@code question 10} (QP and card prose) — but never the
     *       {@code Total for Question 10} footer of the previous question;</li>
     *   <li>{@code 10 (a)} / {@code 10(a)} — the QP header with a part letter;</li>
     *   <li>{@code 10 | (a)} — the MS answer-table row.</li>
     * </ol>
     */
    private static boolean contentMarks(String content, int qnum) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String n = Integer.toString(qnum);
        Pattern prose = Pattern.compile("(?i)(?<!total for )\\bquestion\\s*0*" + n + "\\b");
        Pattern header = Pattern.compile("\\b0*" + n + "\\s*\\((?=[a-h]\\))");
        Pattern table = Pattern.compile("\\b0*" + n + "\\s*\\|\\s*\\(");
        return prose.matcher(content).find()
                || header.matcher(content).find()
                || table.matcher(content).find();
    }

    // ── serving law + evidence construction ─────────────────────────────────

    /**
     * The T-C20 learner-serving gate, branch 2 (knowledge-layer / document
     * branch — used by the card tier): a VALIDATED document serves; a
     * SUGGESTED or REJECTED one never does. The paper branch (tier 1) does
     * NOT go through here — see {@code documentQuestionChunks}.
     */
    private boolean servesUnderServingLaw(Document doc) {
        return "VALIDATED".equals(doc.validationState());
    }

    private static EvidenceItem evidence(Document doc, DocumentChunk chunk) {
        return EvidenceItem.fromChunk(doc.id(), doc.documentId(), doc.docVersion(),
                chunk.id(), chunk.chunkIndex(), doc.kind().name(), chunk.content(),
                chunk.pageStart(), chunk.pageEnd(),
                chunk.elementIds() == null ? List.of() : chunk.elementIds(),
                "paper-question-resolver", 1.0);
    }
}
