package com.syllabai.tutor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One turn of the chat transcript the client sends back with a follow-up ask
 * (Tutor working memory, s139). Working memory is deliberately CLIENT-HELD:
 * the transcript is the learner's own visible conversation, passed per-ask and
 * never persisted server-side — the LIM contract (raw text lives only in
 * research telemetry; cross-session threading stays rejected) is untouched.
 *
 * <p>History is learner-supplied input and is treated with the same distrust
 * as the question itself: roles are whitelisted, citation markers
 * {@code [n]}/{@code 【n】} are stripped from prior assistant answers (their
 * numbers belong to SOURCES that are not in the new prompt), every turn is
 * length-bounded and only the most recent {@link #MAX_HISTORY_TURNS} turns
 * survive. Sanitization happens once, at the service boundary, so the
 * generator and the retrieval-query builder only ever see clean turns.</p>
 *
 * @param role "user" (the learner) or "assistant" (the tutor's earlier answer)
 * @param text the turn's text, sanitized (markers stripped, bounded)
 */
public record ConversationTurn(String role, String text) {

    /** Server-side cap on turns per ask — the client sends fewer (8). */
    public static final int MAX_HISTORY_TURNS = 12;

    /** Per-turn bound; tutor answers are capped at ~200 words, so 2000 chars
     *  is generous headroom that still bounds a hostile payload. */
    public static final int MAX_TURN_CHARS = 2000;

    /** Citation markers as the chat surfaces produce them (s138 pipeline):
     *  [n]/【n】 with 1–3 digits — the same shape the web ChatMarkdown plugin
     *  rewrites. Wider numbers (e.g. [2025]) are content, not markers. */
    private static final Pattern CITATION_MARKER =
            Pattern.compile("\\s*[\\[【][0-9]{1,3}[\\]】]");

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * Remove {@code [n]}/{@code 【n】} citation markers from a tutor answer
     * (s140: also used by the §22 session store — a stored transcript renders
     * as the learner-visible prose, and its source numbers belong to the
     * KA_RAG_COMPLETED telemetry row, the citation archive of record).
     * Public static so the session store shares the exact pattern.
     */
    public static String stripCitationMarkers(String text) {
        return text == null ? null : CITATION_MARKER.matcher(text).replaceAll("");
    }

    /**
     * Normalize one raw client turn; returns null when it carries nothing
     * usable (unknown/blank role, blank text) — callers drop nulls rather
     * than failing the whole ask on one bad turn.
     */
    public static ConversationTurn of(String role, String text) {
        if (role == null || text == null) {
            return null;
        }
        String normalizedRole = role.strip();
        boolean assistant = ROLE_ASSISTANT.equals(normalizedRole);
        if (!assistant && !ROLE_USER.equals(normalizedRole)) {
            return null;
        }
        // Assistant turns systematically carry [n] markers whose numbers refer
        // to sources absent from the next prompt — strip them. User turns keep
        // their text verbatim (a learner typing "[2]" is quoting, not citing).
        String cleaned = assistant ? stripCitationMarkers(text) : text;
        cleaned = cleaned.strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return new ConversationTurn(normalizedRole,
                cleaned.length() <= MAX_TURN_CHARS
                        ? cleaned : cleaned.substring(0, MAX_TURN_CHARS) + "…");
    }

    /**
     * Bound a history list for pipeline use: every turn is re-normalized
     * through {@link #of} (the service layer is the policy boundary — direct
     * callers of {@code KaRagService.ask} get the same marker-stripping and
     * length bounds as the controller path), unusable turns drop out, and
     * only the most recent {@link #MAX_HISTORY_TURNS} survive. Null-safe.
     */
    public static List<ConversationTurn> sanitize(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ConversationTurn> clean = new ArrayList<>(history.size());
        for (ConversationTurn turn : history) {
            ConversationTurn normalized = turn == null
                    ? null : of(turn.role(), turn.text());
            if (normalized != null) {
                clean.add(normalized);
            }
        }
        if (clean.size() <= MAX_HISTORY_TURNS) {
            return List.copyOf(clean);
        }
        return List.copyOf(clean.subList(clean.size() - MAX_HISTORY_TURNS, clean.size()));
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }
}
