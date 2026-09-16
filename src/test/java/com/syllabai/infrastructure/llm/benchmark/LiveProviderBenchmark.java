package com.syllabai.infrastructure.llm.benchmark;

import com.syllabai.infrastructure.llm.FailoverLlmChain;
import com.syllabai.infrastructure.llm.LlmChainConfig;
import com.syllabai.infrastructure.llm.LlmChainProperties;
import com.syllabai.infrastructure.llm.LlmFailureClass;
import com.syllabai.infrastructure.llm.LlmMode;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmProviderFailureClassifier;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ADR-023 Slice G — reproducible provider benchmark, STRICTLY live-gated.
 *
 * <p>Runs ONLY when {@code LIVE_LLM_TESTS=explicit} (and real API keys are present
 * in the environment); it is skipped silently otherwise, so ordinary builds never
 * touch a provider. This harness MEASURES — it does not score providers as "best":
 * success rate, latency, timeout/rate-limit/malformed rates and fallback behaviour
 * per provider over a small representative dataset (ordinary tutor query,
 * specification-point-grounded query, exam-question explanation, CLA context
 * summary, structured JSON generation).</p>
 *
 * <p>Run: {@code LIVE_LLM_TESTS=explicit SYLLABAI_GROQ_API_KEY=… SYLLABAI_GEMINI_API_KEY=…
 * SYLLABAI_OPENROUTER_API_KEY=… mvn test -Dtest=LiveProviderBenchmark}
 * Results land in {@code target/benchmark/benchmark-results.json}. Never pass
 * credentials inside the repository.</p>
 */
@EnabledIfEnvironmentVariable(named = "LIVE_LLM_TESTS", matches = "explicit")
class LiveProviderBenchmark {

    /** Representative SyllabAI workload — NOT generic chatbot prompts. */
    private static List<Case> dataset() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("tutor_ordinary",
                "You are a chemistry tutor. Answer from the SOURCES only, cite as [1].",
                "SOURCES:\n[1] Ionic bonds form by transfer of electrons between metal and non-metal atoms.\n\nQUESTION:\nWhy does sodium chloride conduct electricity when molten but not when solid?"));
        cases.add(new Case("tutor_spec_grounded",
                "You are an Edexcel IGCSE Chemistry (4CH1) tutor. Ground every claim in the "
                        + "SpecificationPoint codes given in SOURCES; never invent spec references.",
                "SOURCES:\n[1] (spec topic 4CH1-1c-09) Calculation of reacting masses using moles.\n\nQUESTION:\nWhat mass of CO2 forms when 10 g of CaCO3 fully decomposes?"));
        cases.add(new Case("exam_explanation",
                "Explain the marking reasoning for the exam part, referencing the scheme wording.",
                "QUESTION: State two observations when magnesium reacts with dilute hydrochloric acid. (2)\nMARK SCHEME: effervescence/fizzing (1); magnesium decreases in size/disappears (1)."));
        cases.add(new Case("cla_context_summary",
                "Summarise the learner's interaction context for a study-plan hint in at most 60 words; "
                        + "do not reveal internal probabilities.",
                "LEARNER CONTEXT: 5 attempts on electrolysis topic, last two incorrect; timed accuracy 0.4 "
                        + "vs untimed 0.8; asked for help twice this week."));
        cases.add(new Case("structured_generation",
                "Return ONLY a JSON object {\"topic\": string, \"misconception\": string, \"action\": string} "
                        + "with no surrounding text.",
                "Learner wrote 'the mass changes during a chemical reaction in a closed flask'. Topic: "
                        + "conservation of mass. Produce the JSON."));
        return cases;
    }

    private record Case(String name, String systemPrompt, String userPrompt) {
        LlmRequest toRequest() {
            return LlmRequest.withOptions(systemPrompt, userPrompt, 0.2, 500);
        }
    }

    private record Row(String provider, String configuredModel, String caseName, boolean success,
                       String failureClass, long latencyMs, int textLength) {
    }

    @org.junit.jupiter.api.Test
    void benchmarkProviders() throws IOException {
        // real chain from the environment's keys; TEST mode would fail closed here,
        // so live runs must explicitly use LIVE wiring
        LlmChainProperties properties = new LlmChainProperties(
                new LlmChainProperties.Groq(bool("SYLLABAI_GROQ_ENABLED", true),
                        System.getenv("SYLLABAI_GROQ_API_KEY"), null, System.getenv("SYLLABAI_GROQ_MODEL")),
                new LlmChainProperties.Gemini(bool("SYLLABAI_GEMINI_ENABLED", true),
                        System.getenv("SYLLABAI_GEMINI_API_KEY"), System.getenv("SYLLABAI_GEMINI_MODEL")),
                new LlmChainProperties.OpenRouter(bool("SYLLABAI_OPENROUTER_ENABLED", true),
                        System.getenv("SYLLABAI_OPENROUTER_API_KEY"), null, System.getenv("SYLLABAI_OPENROUTER_MODEL")),
                new LlmChainProperties.Chain(60, 60, 100, 100000),
                Map.of(),
                LlmMode.LIVE);
        FailoverLlmChain chain = new LlmChainConfig(properties).failoverLlmChain(List.of());

        List<Row> rows = new ArrayList<>();
        for (Case c : dataset()) {
            // attempt each member directly (isolated provider measurements) AND the
            // chain (fallback behaviour), mirroring §18's infrastructure metrics
            for (String member : chain.memberHealth().keySet()) {
                chain.member(member).ifPresent(provider -> {
                    if (!provider.available()) {
                        rows.add(new Row(member, "-", c.name(), false, "UNCONFIGURED", 0, 0));
                        return;
                    }
                    rows.add(measure(member, providerDefaultModel(chain, member), c,
                            () -> provider.generate(c.toRequest())));
                });
            }
            rows.add(measure("chain", "failover", c, () -> chain.generate(c.toRequest())));
        }

        Path out = Path.of("target", "benchmark");
        Files.createDirectories(out);
        Files.writeString(out.resolve("benchmark-results.json"), toJson(rows, Instant.now()));
        System.out.println("benchmark written: " + out.resolve("benchmark-results.json"));
    }

    private String providerDefaultModel(FailoverLlmChain chain, String member) {
        var snapshot = chain.memberHealth().get(member);
        return snapshot == null ? "-" : String.valueOf(snapshot.effectiveModel());
    }

    private Row measure(String provider, String model, Case c,
                        java.util.function.Supplier<LlmResponse> call) {
        long started = System.nanoTime();
        try {
            LlmResponse response = call.get();
            long latency = (System.nanoTime() - started) / 1_000_000;
            boolean malformed = response.text() == null || response.text().isBlank();
            return new Row(provider, model, c.name(), !malformed,
                    malformed ? LlmFailureClass.INVALID_RESPONSE.name() : null, latency,
                    response.text() == null ? 0 : response.text().length());
        } catch (LlmProviderException e) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            return new Row(provider, model, c.name(), false, e.failureClass().name(), latency, 0);
        } catch (RuntimeException e) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            return new Row(provider, model, c.name(), false,
                    LlmProviderFailureClassifier.classify(e).name(), latency, 0);
        }
    }

    private static boolean bool(String env, boolean dflt) {
        String value = System.getenv(env);
        return value == null ? dflt : Boolean.parseBoolean(value);
    }

    private static String toJson(List<Row> rows, Instant runAt) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"runAt\": \"").append(runAt).append("\",\n");
        json.append("  \"gate\": \"LIVE_LLM_TESTS=explicit\",\n");
        json.append("  \"note\": \"measurements only - no 'best provider' scoring (ADR-023 slice G)\",\n");
        json.append("  \"rows\": [\n");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            json.append("    {\"provider\": \"").append(r.provider())
                    .append("\", \"configuredModel\": \"").append(r.configuredModel())
                    .append("\", \"case\": \"").append(r.caseName())
                    .append("\", \"success\": ").append(r.success())
                    .append(", \"failureClass\": ")
                    .append(r.failureClass() == null ? "null" : "\"" + r.failureClass() + "\"")
                    .append(", \"latencyMs\": ").append(r.latencyMs())
                    .append(", \"textLength\": ").append(r.textLength())
                    .append("}").append(i < rows.size() - 1 ? "," : "").append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }
}
