package com.syllabai.assessment;

import com.syllabai.assessment.dto.QuestionFamilyView;
import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ONE owner of the whole-question family rule (session-121): how row-level
 * servable questions reassemble into the SME questions the learner actually
 * sees — the demo's serving logic ({@code build_bundles.py}) ported server-side
 * from the session-120 client module {@code exam-families.ts}, which was
 * verified against the production corpus.
 *
 * <p>Rules (production-verified, session-120 forensics):</p>
 * <ul>
 *   <li>a corpus row's external ref is {@code sme-eq-<unit>-<n>-<slug>-q<N>}
 *       plus an optional part suffix {@code -p<M>} (one MCQ part row) or
 *       {@code -s} (the structured section row of a mixed question); every row
 *       of a family carries the SAME topic tags, so a topic-scoped list always
 *       contains the whole family;</li>
 *   <li>difficulty is uniform within a family;</li>
 *   <li>{@code qN} equals the SME question's 0-based page order, so families
 *       sort by source then {@code qN} — the SME page order;</li>
 *   <li>23 of the 46 mixed families interleave the structured section among the
 *       MCQ parts in a way the refs alone cannot express — those member orders
 *       are pinned below, derived from the demo bundle built from the same
 *       corpus (member sets verified 1:1 against the production refs).</li>
 * </ul>
 *
 * <p>Anything outside the SME ref convention (seed MCQs, past-paper refs like
 * {@code WCH11-2022-01-03a}, null refs) is its own single-member family keyed
 * by row id, served after the corpus.</p>
 */
public class QuestionFamilyAssembler {

    /** {@code sme-eq-<source>-q<N>} + optional {@code -p<M>} / {@code -s}. */
    private static final Pattern SME_REF = Pattern.compile("^(sme-eq-.*)-q(\\d+)(-p(\\d+)|-s)?$");

    /** SME part order for the interleaved families (suffix sequence per family
     * key). Families absent here use the default order: plain row, then
     * {@code -p1..pN}, then {@code -s}. Ported verbatim from the production
     * forensics — 23 families, byte-identical keys. */
    private static final Map<String, List<String>> FAMILY_PART_ORDER = Map.ofEntries(
            Map.entry("sme-eq-1-1-states-of-matter-q16", List.of("-p1", "-s", "-p2")),
            Map.entry("sme-eq-1-2-elements-compounds-and-mixtures-q18", List.of("-s", "-p1")),
            Map.entry("sme-eq-1-2-elements-compounds-and-mixtures-q24", List.of("-s", "-p1")),
            Map.entry("sme-eq-1-8-metallic-bonding-q3", List.of("-s", "-p1", "-p2")),
            Map.entry("sme-eq-1-8-metallic-bonding-q4", List.of("-p1", "-s", "-p2")),
            Map.entry("sme-eq-2-2-group-7-halogens-q16", List.of("-s", "-p1")),
            Map.entry("sme-eq-2-4-reactivity-series-q4", List.of("-s", "-p1")),
            Map.entry("sme-eq-2-4-reactivity-series-q18", List.of("-s", "-p1")),
            Map.entry("sme-eq-2-5-extraction-and-uses-of-metals-q8", List.of("-s", "-p1")),
            Map.entry("sme-eq-2-6-acids-alkalis-and-titrations-q11", List.of("-s", "-p1")),
            Map.entry("sme-eq-2-7-acids-bases-and-salt-preparations-q3", List.of("-s", "-p1")),
            Map.entry("sme-eq-3-3-reversible-reactions-and-equilibria-q3", List.of("-s", "-p1")),
            Map.entry("sme-eq-3-3-reversible-reactions-and-equilibria-q4", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-2-crude-oil-q15", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-3-alkanes-q3", List.of("-s", "-p1", "-p2")),
            Map.entry("sme-eq-4-4-alkenes-q4", List.of("-s", "-p1", "-p2")),
            Map.entry("sme-eq-4-4-alkenes-q8", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-5-alcohols-q4", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-6-carboxylic-acids-q3", List.of("-p1", "-p2", "-s", "-p3")),
            Map.entry("sme-eq-4-6-carboxylic-acids-q4", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-7-esters-q3", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-7-esters-q10", List.of("-s", "-p1")),
            Map.entry("sme-eq-4-8-synthetic-polymers-q9", List.of("-s", "-p1")));

    /** sorts after every real source, so non-corpus rows serve after the corpus */
    private static final String NON_SME_SOURCE = "￿";

    private record RefParse(String family, String suffix, String source, long qNum) {
    }

    /**
     * The family identity of one row — the base ref ({@code sme-eq-…-qN}) for
     * corpus rows, the row id for everything else. Exposed separately because
     * the taxonomy census needs the grouping key over raw {@link Question}
     * entities, before any projection happens.
     */
    public String familyKey(String externalRef, UUID rowId) {
        return parse(externalRef, rowId).family();
    }

    /** Group a topic's (or subject's, or the whole bank's) servable rows into
     * whole SME questions, SME-page-ordered — the demo's serving unit. */
    public List<QuestionFamilyView> assemble(List<StudentQuestionView> rows) {
        Map<String, List<RowWithParse>> byFamily = new LinkedHashMap<>();
        for (StudentQuestionView row : rows) {
            RefParse parse = parse(row.externalRef(), row.id());
            byFamily.computeIfAbsent(parse.family(), k -> new ArrayList<>())
                    .add(new RowWithParse(row, parse));
        }

        List<QuestionFamilyView> units = new ArrayList<>(byFamily.size());
        Map<String, RefParse> parseByFamily = new HashMap<>();
        for (Map.Entry<String, List<RowWithParse>> entry : byFamily.entrySet()) {
            List<RowWithParse> bucket = entry.getValue();
            // every member of a family shares source and qNum by construction —
            // keep one parse for the page-order sort below
            parseByFamily.put(entry.getKey(), bucket.get(0).parse());
            bucket.sort(Comparator.comparingInt(
                    r -> memberRank(entry.getKey(), r.parse.suffix())));
            List<StudentQuestionView> parts = bucket.stream().map(r -> r.row).toList();
            boolean multi = bucket.size() > 1 || !bucket.get(0).parse.suffix().isEmpty();
            units.add(new QuestionFamilyView(
                    entry.getKey(),
                    multi ? entry.getKey() : bucket.get(0).row.externalRef(),
                    parts.stream().mapToInt(StudentQuestionView::marks).sum(),
                    parts.get(0).difficulty(),
                    parts.stream().allMatch(p -> !"STRUCTURED".equals(p.type())) ? "MCQ" : "STRUCTURED",
                    multi,
                    parts));
        }

        // SME page order: source topic, then question number (qN = 0-based order)
        units.sort(Comparator
                .comparing((QuestionFamilyView u) -> parseByFamily.get(u.key()).source(),
                        QuestionFamilyAssembler::compareSource)
                .thenComparingLong(u -> parseByFamily.get(u.key()).qNum())
                .thenComparing(QuestionFamilyView::key));
        return units;
    }

    private record RowWithParse(StudentQuestionView row, RefParse parse) {
    }

    private RefParse parse(String externalRef, UUID rowId) {
        if (externalRef != null) {
            Matcher m = SME_REF.matcher(externalRef);
            if (m.matches()) {
                return new RefParse(m.group(1) + "-q" + m.group(2),
                        m.group(3) == null ? "" : m.group(3),
                        m.group(1),
                        Long.parseLong(m.group(2)));
            }
        }
        // not an SME corpus ref: its own family, sorts after the corpus
        return new RefParse(rowId == null ? externalRef : rowId.toString(), "", NON_SME_SOURCE, 0L);
    }

    /** numeric-aware slug compare: "1-10-x" sorts after "1-2-x" */
    static int compareSource(String a, String b) {
        String[] sa = a.split("-");
        String[] sb = b.split("-");
        int len = Math.max(sa.length, sb.length);
        for (int i = 0; i < len; i++) {
            String x = i < sa.length ? sa[i] : null;
            String y = i < sb.length ? sb[i] : null;
            if (x == null) return -1;
            if (y == null) return 1;
            Long nx = isNumeric(x) ? Long.parseLong(x) : null;
            Long ny = isNumeric(y) ? Long.parseLong(y) : null;
            if (nx != null && ny != null) {
                if (!nx.equals(ny)) return nx < ny ? -1 : 1;
            } else if (!x.equals(y)) {
                return x.compareTo(y) < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    /** SME member order for a family: pinned order if known, else the default
     * (plain row, then -p1..pN, then -s). */
    private static int memberRank(String family, String suffix) {
        List<String> pinned = FAMILY_PART_ORDER.get(family);
        if (pinned != null) {
            int idx = pinned.indexOf(suffix);
            if (idx >= 0) return idx;
        }
        if (suffix.isEmpty()) return -1;
        if ("-s".equals(suffix)) return 10_000;
        if (suffix.length() > 2 && suffix.startsWith("-p")
                && isNumeric(suffix.substring(2))) {
            return 9_000 + Integer.parseInt(suffix.substring(2));
        }
        return 9_500;
    }
}
