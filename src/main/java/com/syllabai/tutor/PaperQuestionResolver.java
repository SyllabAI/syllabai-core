package com.syllabai.tutor;

import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.FetchQueryParser;
import com.syllabai.content.FetchService;
import com.syllabai.content.FetchService.FetchResult;
import com.syllabai.content.FetchService.FetchPaperHit;
import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.curriculum.CurriculumScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * <p>Three anchors — the bank resolves first (first match wins); the card
 * and content-store anchors compose below it:</p>
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
 *   <li><strong>Content-store paper anchor</strong> — the real QP/MS documents
 *       of the bound identity (V33 chunk mirror: series + year + paper code).
 *       Runs whenever the identity binds, composing with the card tier: the
 *       card pins its metadata pointer chunks and the store pins the
 *       question's own stem and mark-scheme answer chunks. Served only when
 *       the candidate document row is the VALIDATED top version (branch-2
 *       law, the card gate).</li>
 * </ol>
 *
 * <p>Serving law (T-C20) holds on every tier: REJECTED never serves; the
 * paper/document validation gate mirrors
 * {@code ChunkVectorRepository#searchServingEligible}. Emptiness is honest
 * and two-valued, and {@link #resolveWithVerdict} separates the cases: a
 * not-a-paper ask (no parseable identity) returns empty with
 * {@code identityParsed=false} and the unmodified vector+KG path serves the
 * ask exactly as before; a <em>complete</em> paper-question identity
 * (question number + series + year) that binds NOT ONE validated anchor
 * returns empty with {@code identityParsed=true} — the fail-open guard
 * (09-27 adjudication, direction (a)): the caller must refuse honestly
 * instead of letting generic retrieval answer a named paper question from
 * textually-similar wrong-paper chunks. Bank ambiguity (the question
 * resolving in several rows) does NOT disarm the guard: a question that
 * exists only in SUGGESTED bank rows is unservable by the serving law, and
 * the 09-27 G1 probe proved that shape serves wrong-paper bleed when the
 * guard is silenced. The resolver never guesses, never widens.</p>
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
    /** Content-store companion caps (card ≤2 + QP ≤2 + MS ≤2 ≤ the pool limit of 6). */
    private static final int MAX_STORE_QP_ITEMS = 2;
    private static final int MAX_STORE_MS_ITEMS = 2;

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
        return resolveWithVerdict(query, scope).items();
    }

    /**
     * The resolve outcome: the pinned lead items plus the fail-open verdict.
     * {@code identityParsed=true} with empty items is the guard's trigger —
     * the ask carried a complete paper-question identity and nothing
     * validated bound it; the caller refuses, it does not fall back to
     * generic retrieval. {@code identityLabel} is the human-readable echo of
     * the parsed identity for the refusal sentence.
     */
    public record Resolution(List<EvidenceItem> items, boolean identityParsed,
                             String identityLabel) {

        /** Not a paper ask — the pipeline serves the unmodified path. */
        public static Resolution notPaperAsk() {
            return new Resolution(List.of(), false, null);
        }

        /** The identity bound (bank tier) — items always non-empty here. */
        public static Resolution served(List<EvidenceItem> items, String identityLabel) {
            return new Resolution(List.copyOf(items), true, identityLabel);
        }
    }

    /**
     * Resolution with its fail-open verdict. {@code identityParsed} is true
     * exactly when the ask carried a complete paper-question identity
     * (question number + series + year — the card tier's bindable
     * definition). The bank's ambiguity flag does NOT disarm the verdict:
     * it fires when the question resolves in several rows, which per the
     * serving law can only mean SUGGESTED rows — unservable either way
     * (09-27 G1 evidence: JUN-2019 q10 bank rows on SUGGESTED 1C/1CR).
     * An exception mid-resolution leaves the anchor state unknown, so the
     * verdict degrades to {@code identityParsed=false}: the resolver never
     * gates an ask on unknown state — a state that is KNOWN because the parse
     * is pure in-memory work is not degraded: under a complete parsed identity
     * an infrastructure failure keeps {@code identityParsed=true} so the
     * fail-open guard refuses deterministically (L3, audit 2026-10-02 — the
     * old catch-all downgraded the verdict to "not a paper ask" exactly when
     * the corpus was unavailable, and the ask silently served generic
     * retrieval). (The content-store tier's own catch
     * converts a store error into empty items — under a parsed identity
     * that is a fail-closed refusal, never the wrong-paper serve.)
     */
    public Resolution resolveWithVerdict(String query, CurriculumScope scope) {
        if (query == null || query.isBlank() || scope == null) {
            return Resolution.notPaperAsk();
        }
        FetchResult fetch;
        try {
            fetch = fetchService.fetch(query, scope);
        } catch (RuntimeException e) {
            // L3 (audit 2026-10-02): a fetch failure used to degrade the whole
            // verdict to notPaperAsk — disarming the fail-open guard exactly
            // when the corpus was unavailable. The parse is a pure in-memory
            // step (FetchQueryParser.parse), so the identity verdict is
            // recovered locally: a COMPLETE identity under a DB error is a
            // KNOWN state (nothing bound — the store could not be asked), and
            // the guard's deterministic paper refusal fires instead of the ask
            // silently degrading to generic retrieval. A query that does not
            // parse a complete identity keeps the legacy honest fallback (the
            // vector+KG path serves).
            LOG.warn("paper-question resolver: fetch step failed ({}); "
                    + "verdict recovered from local re-parse", e.getClass().getSimpleName());
            ParsedFetchQuery reparsed = FetchQueryParser.parse(query);
            if (completeIdentity(reparsed)) {
                return new Resolution(List.of(), true, identityLabel(reparsed, query));
            }
            return Resolution.notPaperAsk();
        }

        ParsedFetchQuery parsed = fetch.parsed();
        boolean identityParsed = completeIdentity(parsed);
        try {
            List<EvidenceItem> bankAnchored = bankAnchored(fetch);
            if (!bankAnchored.isEmpty()) {
                return Resolution.served(bankAnchored, identityLabel(parsed, query));
            }
            List<EvidenceItem> pinned = new ArrayList<>(cardAnchored(query, parsed, scope));
            pinned.addAll(contentStoreAnchored(query, parsed, scope));
            return new Resolution(List.copyOf(pinned), identityParsed,
                    identityParsed ? identityLabel(parsed, query) : null);
        } catch (RuntimeException e) {
            LOG.warn("paper-question resolver: resolution failed ({})",
                    e.getClass().getSimpleName());
            if (identityParsed) {
                // L3: the anchor state is KNOWN-bad mid-flight (infrastructure
                // failure after a complete identity parsed) — the fail-open
                // guard refuses deterministically; the verdict is never
                // downgraded to "not a paper ask"
                return new Resolution(List.of(), true, identityLabel(parsed, query));
            }
            // anchor state unknown mid-flight — never gate the ask on unknown
            return Resolution.notPaperAsk();
        }
    }

    /**
     * A complete paper-question identity: question number + series + year —
     * exactly the card tier's "bindable paper-style ask" definition. (A
     * parseDefect fetch has an empty parse, which cannot satisfy this.)
     */
    private static boolean completeIdentity(ParsedFetchQuery parsed) {
        return parsed != null && parsed.qnum() != null && parsed.year() != null
                && parsed.series() != null && !parsed.series().isBlank();
    }

    /**
     * Human-readable echo of the parsed identity for the honest-refusal
     * sentence: "question 10 from the June 2019 paper 2". The paper suffix
     * follows the binding priority (explicit code → bare unit → "paper N"
     * hint); an ask that named no paper reads "… the June 2019 papers".
     */
    private static String identityLabel(ParsedFetchQuery parsed, String query) {
        if (parsed == null) {
            return "that paper question";
        }
        String seriesToken = parsed.series() == null ? "" : parsed.series().strip();
        String series = switch (seriesToken) {
            case "JAN" -> "January";
            case "JUN" -> "June";
            case "NOV" -> "November";
            default -> seriesToken;
        };
        StringBuilder label = new StringBuilder("question ").append(parsed.qnum());
        if (!series.isEmpty()) {
            label.append(" from the ").append(series);
        }
        if (parsed.year() != null) {
            label.append(series.isEmpty() ? " from " : " ").append(parsed.year());
        }
        String unit = parsed.paperCode() != null
                ? unitFromPaperCode(parsed.paperCode()) : parsed.unit();
        if (unit == null || unit.isBlank()) {
            Matcher hint = PAPER_HINT.matcher(query == null ? "" : query);
            if (hint.find()) {
                unit = hint.group(1);
            }
        }
        if (unit != null && !unit.isBlank()) {
            label.append(" paper ").append(unit.toUpperCase());
        } else {
            label.append(" papers");
        }
        return label.toString();
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

    // ── tier 2b: content-store paper anchor (the identity's real QP/MS) ─────

    /**
     * The ingested QUESTION_PAPER / MARK_SCHEME documents of the exact paper
     * identity the ask binds. Question cards are metadata pointers (marks +
     * truncated stem, never answer-bearing); the paired QP/MS chunks are the
     * question's own stem and mark-scheme answers, so they pin as lead
     * evidence right after the card — with or without a surviving card.
     *
     * <p>Identity is the V33 chunk mirror (series + year + paper code, e.g.
     * {@code 4CH1/1C} for "jan 2022 question 4 paper 1"): exact column
     * matching, no content regex — a 1C ask can never bind a 2C/2CR/1CR
     * document, and wrong-paper front-matter chunks (the 09-26 live-pool
     * pollution) are structurally excluded. Exactly one unit wins (the first
     * unit candidate with an accepted document — the card tier's
     * first-match-wins posture); the scope subject code outranks the legacy
     * alias; QP items precede MS items.</p>
     *
     * <p>Serving law (branch-2 posture, the card tier's gate): only the
     * VALIDATED top version of a candidate document pins — SUGGESTED and
     * REJECTED rows are invisible. Both kinds pin whenever the identity
     * binds: a bound ask is a specific-question ask, and the paired mark
     * scheme is the answer source for "give me the answer" and the
     * walkthrough source for "explain" asks alike. Failure is honest and
     * local: a store error keeps the card-tier evidence and serves it alone.</p>
     */
    private List<EvidenceItem> contentStoreAnchored(String query, ParsedFetchQuery parsed,
                                                    CurriculumScope scope) {
        if (parsed == null || parsed.qnum() == null || parsed.year() == null
                || parsed.series() == null || parsed.series().isBlank()) {
            return List.of();   // not a bindable paper-style ask
        }
        try {
            String subjectCode = subjectCode(scope);
            List<String> unitCandidates = unitCandidates(parsed, query);
            if (subjectCode == null || unitCandidates.isEmpty()) {
                return List.of();
            }
            List<String> paperCodes = paperCodes(subjectCode, unitCandidates);
            List<Object[]> identityRows = chunks.findRowIdsByPaperIdentity(
                    parsed.series(), parsed.year(), paperCodes);
            if (identityRows == null || identityRows.isEmpty()) {
                return List.of();
            }
            Map<UUID, String> matchedCode = new LinkedHashMap<>();
            for (Object[] row : identityRows) {
                matchedCode.putIfAbsent((UUID) row[0], (String) row[1]);
            }
            List<Document> accepted = new ArrayList<>();
            for (UUID rowId : matchedCode.keySet()) {
                Optional<Document> doc = documents.findById(rowId);
                if (doc.isEmpty() || !servesUnderServingLaw(doc.get())) {
                    continue;   // branch-2 gate: SUGGESTED/REJECTED rows never pin
                }
                Document candidate = doc.get();
                if (candidate.kind() != Document.Kind.QUESTION_PAPER
                        && candidate.kind() != Document.Kind.MARK_SCHEME) {
                    continue;   // question cards resolve through the card tier
                }
                Optional<Document> top = documents.findTopByDocumentIdOrderByDocVersionDesc(
                        candidate.documentId());
                if (top.isEmpty() || !rowId.equals(top.get().id())) {
                    continue;   // a superseded version row never pins; the top one does
                }
                accepted.add(candidate);
            }
            if (accepted.isEmpty()) {
                return List.of();
            }
            accepted.sort(Comparator
                    .comparingInt((Document d) -> unitIndex(unitCandidates, matchedCode.get(d.id())))
                    .thenComparingInt(d -> matchedCode.get(d.id()).startsWith(subjectCode + "/") ? 0 : 1)
                    .thenComparing(Comparator.comparingInt(Document::docVersion).reversed()));
            String boundUnit = unitOf(matchedCode.get(accepted.get(0).id()));
            List<Document> bound = accepted.stream()
                    .filter(d -> unitOf(matchedCode.get(d.id())).equals(boundUnit))
                    .toList();
            List<EvidenceItem> items = new ArrayList<>();
            pinKind(bound, Document.Kind.QUESTION_PAPER, parsed.qnum(), MAX_STORE_QP_ITEMS, items);
            pinKind(bound, Document.Kind.MARK_SCHEME, parsed.qnum(), MAX_STORE_MS_ITEMS, items);
            return List.copyOf(items);
        } catch (RuntimeException e) {
            LOG.warn("paper-question resolver: content-store anchor failed ({}); keeping the "
                    + "card-tier evidence", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Scope subject first, legacy alias second, units in candidate order. */
    private static List<String> paperCodes(String subjectCode, List<String> unitCandidates) {
        List<String> codes = new ArrayList<>();
        for (String code : List.of(subjectCode, "4CH0")) {
            for (String unit : unitCandidates) {
                String candidate = code + "/" + unit;
                if (!codes.contains(candidate)) {
                    codes.add(candidate);
                }
            }
        }
        return codes;
    }

    /** Match the question's chunks in each document of one kind, capped in total. */
    private void pinKind(List<Document> docs, Document.Kind kind, int qnum,
                         int cap, List<EvidenceItem> items) {
        int pinned = 0;
        for (Document doc : docs) {
            if (doc.kind() != kind || pinned >= cap) {
                continue;
            }
            for (DocumentChunk chunk : matchQuestion(
                    chunks.findByDocumentRowIdOrderByChunkIndexAsc(doc.id()), qnum)) {
                if (pinned >= cap) {
                    break;
                }
                items.add(evidence(doc, chunk));
                pinned++;
            }
        }
    }

    /** "4CH1/1C" → candidate-list index of "1C"; unknown units sort last. */
    private static int unitIndex(List<String> unitCandidates, String paperCode) {
        int idx = unitCandidates.indexOf(unitOf(paperCode));
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    /** "4CH1/1C" → "1C". */
    private static String unitOf(String paperCode) {
        int cut = paperCode.indexOf('/');
        return cut < 0 ? paperCode : paperCode.substring(cut + 1);
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
