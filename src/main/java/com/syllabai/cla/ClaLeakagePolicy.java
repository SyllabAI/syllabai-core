package com.syllabai.cla;

import com.syllabai.tutor.EvidenceItem;

/**
 * The deterministic exam-question answer-leakage gate (CLA contract §7.4:
 * "implemented in application code over resolved ids and attempt state —
 * never delegated to the model, never prompt-only").
 *
 * <p>Two deterministic decisions, both pure functions of the resolved
 * {@link ResourceContext}, the {@link ResponseMode} and the evidence item:</p>
 *
 * <ol>
 *   <li><b>Mode admission</b> — {@code CHECK} on a question context requires
 *       attempt evidence for the requesting learner and question (§7.3, read
 *       from attempt history — the same substrate as Review Hub). Pre-attempt
 *       CHECK throws {@link AttemptRequiredException} before retrieval or
 *       generation runs.</li>
 *   <li><b>Evidence eligibility</b> — what may enter the SOURCES block:</li>
 * </ol>
 *
 * <table>
 *   <tr><th>context</th><th>mark-scheme DOCUMENT chunks</th><th>rule</th></tr>
 *   <tr>
 *     <td>{@code PAST_PAPER_QUESTION}</td>
 *     <td>never in step 2</td>
 *     <td>canonical mark-scheme chunks are PAGE-level and cannot be bound to
 *         one question deterministically — serving them could leak a sibling
 *         question's pending points. Post-attempt full feedback grounds on the
 *         question's OWN VALIDATED assessment-model scheme points instead
 *         (question-granular, resolved by ids), which the pipeline prepends as
 *         synthesized evidence.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code KG_TOPIC}</td>
 *     <td>allowed (step-1 behavior, tutor parity)</td>
 *     <td>unchanged — a free topic discussion is not anchored to assessment
 *         content, and the free Tutor already serves these chunks.</td>
 *   </tr>
 * </table>
 *
 * <p>{@code HINT} on a question context additionally never receives the
 * synthesized scheme-point evidence, pre- or post-attempt (§7.2: scaffolding
 * only). All rules are pinned by {@code ClaLeakagePolicyTest} and exercised
 * end-to-end by the CI-mandatory negative suite in {@code ClaFlowIT} (§7.5).</p>
 */
public final class ClaLeakagePolicy {

    private ClaLeakagePolicy() {
    }

    /** §7.3/§7.4: CHECK requires attempt evidence on question contexts. */
    public static void checkModeAdmission(ResourceContext context, ResponseMode mode) {
        if (context.isQuestionContext() && mode == ResponseMode.CHECK
                && !Boolean.TRUE.equals(context.attempted())) {
            throw new AttemptRequiredException();
        }
    }

    /** §7.2/§7.4: may a mark-scheme DOCUMENT chunk enter evidence for this context? */
    public static boolean markSchemeDocumentChunkAllowed(ResourceContext context,
                                                         ResponseMode mode) {
        // step 2, question contexts: never (page-level chunks cannot be bound
        // to one question deterministically — strict-safe exclusion)
        return !context.isQuestionContext();
    }

    /** §7.2: may the question's OWN scheme-point evidence enter a HINT? Never. */
    public static boolean schemePointEvidenceAllowed(ResourceContext context,
                                                     ResponseMode mode) {
        return context.isQuestionContext()
                && Boolean.TRUE.equals(context.attempted())
                && mode != ResponseMode.HINT;
    }

    /** the deterministic evidence filter applied post-fusion, pre-gate */
    public static boolean evidenceEligible(ResourceContext context, ResponseMode mode,
                                           EvidenceItem item) {
        if (item.source() == EvidenceItem.EvidenceSource.MARK_SCHEME
                && context.isQuestionContext()
                && item.documentRowId() != null) {
            // a DOCUMENT chunk from the content store (rowId present) — excluded
            // on question contexts entirely (see class table)
            return false;
        }
        return true;
    }
}
