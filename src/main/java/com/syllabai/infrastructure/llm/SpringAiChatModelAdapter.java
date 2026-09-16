package com.syllabai.infrastructure.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts a Spring AI {@link ChatModel} to the {@link LlmProvider} port (Master Spec
 * §23 Adapter pattern, §26 abstraction). Works for the OpenAI-compatible models
 * (Groq, OpenRouter) and the Google GenAI model (Gemini) alike — provider specifics
 * stay inside the bean construction in {@code LlmChainConfig}.
 */
public class SpringAiChatModelAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatModelAdapter.class);

    /**
     * Daemon, cached worker pool so a hung provider HTTP call cannot pin a
     * request thread forever: the caller gives up after {@code timeoutSeconds}
     * (§26.1 {@code syllabai.llm.chain.timeout-seconds}) and the failover chain
     * moves to the next provider. Stranded workers are daemons and die with
     * the JVM; the health cooldown stops further calls to the dead provider.
     */
    private static final ExecutorService CALL_POOL = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "llm-provider-call");
                t.setDaemon(true);
                return t;
            });

    private final String providerName;
    private final ChatModel chatModel;
    private final boolean configured;
    private final LlmProviderHealth health;
    private final int timeoutSeconds;
    /**
     * Builds the PROMPT-level runtime options for a request. Must produce the
     * CONCRETE options type the underlying Spring AI ChatModel asserts on
     * (OpenAiChatOptions / GoogleGenAiChatOptions) — Spring AI 2.0.x rejects a
     * generic ChatOptions with ClassCastException/Assert.isInstanceOf at call
     * time (2026-09-14 outage root cause). Null = legacy generic fallback.
     */
    private final Function<LlmRequest, ChatOptions> runtimeOptionsFactory;

    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean configured) {
        this(providerName, chatModel, configured, 3, 60, 30, null);
    }

    /** Threshold/cooldown come from {@code syllabai.llm.chain.*} via LlmChainConfig. */
    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean configured,
                                    int failureThreshold, int cooldownSeconds) {
        this(providerName, chatModel, configured, failureThreshold, cooldownSeconds, 30, null);
    }

    /** Legacy wiring without a runtime-options factory (generic ChatOptions fallback). */
    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean configured,
                                    int failureThreshold, int cooldownSeconds, int timeoutSeconds) {
        this(providerName, chatModel, configured, failureThreshold, cooldownSeconds, timeoutSeconds, null);
    }

    /** Legacy wiring incl. per-call timeout and the concrete runtime-options factory. */
    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean configured,
                                    int failureThreshold, int cooldownSeconds, int timeoutSeconds,
                                    Function<LlmRequest, ChatOptions> runtimeOptionsFactory) {
        this(providerName, chatModel, configured, configured, failureThreshold, cooldownSeconds,
                timeoutSeconds, 0, null, runtimeOptionsFactory);
    }

    /**
     * Full wiring (ADR-023): enabled/configured are exposed separately so the admin
     * health output can distinguish "disabled by configuration" from "enabled but key
     * missing"; {@code dailyBudget} is the configured LOCAL routing guard (not a
     * provider-quota claim); {@code effectiveModel} is the provider default model.
     */
    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean enabled,
                                    boolean configured, int failureThreshold, int cooldownSeconds,
                                    int timeoutSeconds, int dailyBudget, String effectiveModel,
                                    Function<LlmRequest, ChatOptions> runtimeOptionsFactory) {
        this.providerName = providerName;
        this.chatModel = chatModel;
        this.configured = configured;
        this.health = new LlmProviderHealth(enabled, configured, failureThreshold, cooldownSeconds,
                dailyBudget, effectiveModel);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.runtimeOptionsFactory = runtimeOptionsFactory;
    }

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public boolean available() {
        // ADR-023: a provider that consumed its configured LOCAL daily budget is
        // ineligible until the UTC day rolls over — same treatment as cooldown.
        return configured && !health.inCooldown() && !health.budgetExhausted();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        if (!configured || chatModel == null) {
            throw new LlmProviderException(providerName, "provider not configured", null);
        }
        long started = System.nanoTime();
        try {
            ChatOptions runtimeOptions = options(request);
            Prompt prompt = runtimeOptions == null
                    ? new Prompt(messages(request))
                    : new Prompt(messages(request), runtimeOptions);
            ChatResponse response = callWithTimeout(prompt);
            String text = extractText(response);
            Usage usage = response.getMetadata().getUsage();
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            health.recordSuccess();
            // report the model actually used: an explicit request/pin model overrides
            // the provider default (§19 reproducibility — telemetry must not claim the
            // default model when an experiment pin routed to a specific one)
            String effectiveModel = (request.model() != null && !request.model().isBlank())
                    ? request.model()
                    : (chatModel.getDefaultOptions() == null ? null
                            : chatModel.getDefaultOptions().getModel());
            return new LlmResponse(
                    text,
                    providerName,
                    effectiveModel,
                    latencyMs,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens());
        } catch (LlmProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            // ADR-023: classify ONCE at the adapter boundary, from exception TYPE and
            // HTTP status — never by parsing error strings. The SDK error text (still
            // no credential material) stays in the message for diagnosability.
            LlmFailureClass failureClass = LlmProviderFailureClassifier.classify(e);
            String causeSummary = e.getClass().getSimpleName() + ": " + e.getMessage();
            // Real cause must reach BOTH the health snapshot and the log — the
            // provider-SDK exception text is what tells a 403 dead key apart from a
            // 404 retired model or a 429 quota outage (2026-09-14 outage lesson).
            log.warn("LLM provider {} failed after {} ms ({}): {}", providerName, latencyMs,
                    failureClass,
                    causeSummary.length() > 300 ? causeSummary.substring(0, 300) : causeSummary);
            health.recordFailure(causeSummary, failureClass);
            throw new LlmProviderException(providerName,
                    "generation failed (" + (causeSummary.length() > 200
                            ? causeSummary.substring(0, 200) : causeSummary) + ")",
                    e, failureClass);
        }
    }

    @Override
    public LlmProviderHealth health() {
        return health;
    }

    /**
     * Runs the blocking provider call on the shared daemon pool and gives up
     * after {@code syllabai.llm.chain.timeout-seconds} (default 30s), so a hung
     * Groq/Gemini/OpenRouter call degrades into a counted failure + cooldown
     * instead of an indefinitely blocked request thread.
     */
    private ChatResponse callWithTimeout(Prompt prompt) {
        Future<ChatResponse> pending = CALL_POOL.submit(() -> chatModel.call(prompt));
        try {
            return pending.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            pending.cancel(true);
            health.recordFailure("TimeoutException: no response within " + timeoutSeconds + "s",
                    LlmFailureClass.TIMEOUT);
            throw new LlmProviderException(providerName,
                    "generation timed out after " + timeoutSeconds + "s", te,
                    LlmFailureClass.TIMEOUT);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;   // existing generate() catch records the failure
            }
            throw new IllegalStateException(providerName + " generation failed", cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(providerName + " generation interrupted", ie);
        }
    }

    private java.util.List<org.springframework.ai.chat.messages.Message> messages(LlmRequest request) {
        java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userPrompt()));
        return messages;
    }

    private ChatOptions options(LlmRequest request) {
        if (runtimeOptionsFactory != null) {
            return runtimeOptionsFactory.apply(request);
        }
        if (request.temperature() == null && request.maxTokens() == null
                && (request.model() == null || request.model().isBlank())) {
            return null;    // fall back to model defaults
        }
        ChatOptions.Builder<?> builder = ChatOptions.builder();
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        if (request.model() != null && !request.model().isBlank()) {
            builder.model(request.model());   // per-experiment model pin (§26.1)
        }
        return builder.build();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        AssistantMessage message = response.getResult().getOutput();
        return message.getText() == null ? "" : message.getText();
    }
}
