package com.syllabai.tutor;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MatchedTopic;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MisconceptionSignal;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.PrerequisiteLink;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * KG-side retrieval (T-024): deterministic intent resolution over the
 * curriculum structure. Query tokens are matched against UNIT/TOPIC/SUBTOPIC
 * titles (whole-token, case-insensitive); match score is specificity — the
 * fraction of the node title the query actually names — so long generic
 * titles rank below precisely-named ones. No LLM invents entities here
 * (Master Spec §7): a topic is "about the question" only if the question
 * literally names (part of) its title.
 *
 * <p>For each matched topic the retriever also gathers prerequisite chains
 * (remediation context) and attached misconceptions — the pedagogical
 * context the tutor policy (T-026) will later act on.</p>
 *
 * <p>Known v0 limitation, documented honestly: matching is title-only
 * (descriptions are extraction metadata, not reliable match targets) with
 * plural normalization but no real stemming or synonymy — "moles" matches a
 * topic titled "Mole calculations", but "amount of substance" phrasing needs
 * the exact tokens. Teachers can rely on exact topic titles; a smarter
 * matcher (synonyms from KG descriptions) is a later, still-deterministic
 * upgrade.</p>
 */
@Component
public class GraphKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(GraphKnowledgeRetriever.class);

    /**
     * Precision floor (productization §7): a match must name at least ~1/3 of
     * the node title before the question can be called "about" that topic.
     * Calibrated on the live P6 battery: real chemistry asks land at 0.5–1.0
     * ("moles" onto "Mole calculations" = 0.5; "dynamic equilibrium" = 1.0),
     * while the observed false positive — a Hamlet question's "plot" leaking
     * onto a 5-token solubility title — scored 0.2. Below the floor the topic
     * simply does not match, which feeds the fail-closed refusal path (never
     * a fabricated answer). Grounding stays strict; recall on genuine asks is
     * untouched.
     */
    static final double MIN_SPECIFICITY = 0.30;

    private static final Set<String> STOP_TOKENS = Set.of(
            "the", "and", "for", "are", "what", "which", "how", "does", "why", "with",
            "from", "into", "this", "that", "explain", "describe", "state", "define",
            "about", "help", "me", "my", "can", "you", "give", "using", "use", "when");

    private final KnowledgeGraphService graph;

    public GraphKnowledgeRetriever(KnowledgeGraphService graph) {
        this.graph = graph;
    }

    @Override
    public KnowledgeContext retrieve(String query, int maxTopics) {
        Set<String> queryTokens = tokensOf(query);
        if (queryTokens.isEmpty()) {
            return new KnowledgeContext(List.of(), List.of(), List.of());
        }

        // token → nodes whose titles contain it (ranked later by specificity).
        // §7: only VALIDATED curriculum nodes may inform what serves to learners —
        // SUGGESTED/UNVALIDATED seeds are invisible to the tutor until a teacher
        // validates them (mirrors ServableQuestionSpec for assessment).
        Map<UUID, MatchAccumulator> matches = new LinkedHashMap<>();
        for (KnowledgeNode node : graph.structureNodes()) {
            if (node.validationStatus() != KnowledgeNode.ValidationStatus.VALIDATED) {
                continue;
            }
            Set<String> titleTokens = tokensOf(node.title());
            if (titleTokens.isEmpty()) {
                continue;
            }
            long named = titleTokens.stream().filter(queryTokens::contains).count();
            if (named == 0) {
                continue;
            }
            double specificity = (double) named / titleTokens.size();
            if (specificity < MIN_SPECIFICITY) {
                continue;   // too weak to call the question "about" this topic
            }
            matches.put(node.id(), new MatchAccumulator(node, named, specificity));
        }

        List<MatchedTopic> ranked = matches.values().stream()
                .sorted(Comparator.comparingDouble(MatchAccumulator::specificity).reversed()
                        .thenComparing(m -> m.node.code()))
                .limit(Math.max(1, maxTopics))
                .map(m -> new MatchedTopic(m.node.id(), m.node.code(), m.node.title(), m.specificity))
                .toList();

        List<PrerequisiteLink> prerequisites = new ArrayList<>();
        List<MisconceptionSignal> misconceptions = new ArrayList<>();
        for (MatchedTopic topic : ranked) {
            for (var withDepth : graph.prerequisiteChain(topic.nodeId())) {
                prerequisites.add(new PrerequisiteLink(topic.nodeId(),
                        withDepth.id(), withDepth.title(), withDepth.depth()));
            }
            for (var misconception : graph.misconceptions(topic.nodeId())) {
                misconceptions.add(new MisconceptionSignal(topic.nodeId(),
                        misconception.id(), misconception.title()));
            }
        }

        log.debug("intent matched {} topic(s) for query of {} token(s)", ranked.size(),
                queryTokens.size());
        return new KnowledgeContext(ranked, prerequisites, misconceptions);
    }

    /**
     * Query/title tokenizer: lowercase, alphanumeric split, stop tokens and
     * 1–2 char fragments dropped, and a symmetric plural normalization
     * (trailing "s" stripped when 4+ chars) applied to BOTH sides — so
     * "moles" matches a title containing "mole", and "Equations" matches
     * "equation". Symmetry keeps the match deterministic: both sides use
     * this exact function, never a stemmer with a vocabulary.
     */
    private Set<String> tokensOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 3 || STOP_TOKENS.contains(raw)) {
                continue;
            }
            tokens.add(normalizePlural(raw));
        }
        return tokens;
    }

    private static String normalizePlural(String token) {
        if (token.length() >= 4 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private record MatchAccumulator(KnowledgeNode node, long namedTokens, double specificity) {
    }
}
