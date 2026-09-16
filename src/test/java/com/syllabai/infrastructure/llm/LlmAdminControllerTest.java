package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 Slice E — the admin chain-health contract: per-provider snapshots must
 * expose enough to understand routing (enabled/configured/healthy/coolingDown/
 * requestsToday/dailyBudget/remainingLocalBudget/lastFailureClass/effectiveModel)
 * WITHOUT exposing API keys, authorization headers, prompts or learner data.
 */
class LlmAdminControllerTest {

    @Test
    @DisplayName("chain-health reports mode-free aggregate + per-provider ADR-023 fields")
    @SuppressWarnings("unchecked")
    void chainHealthShape() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 50)
                .alwaysFails(LlmFailureClass.RATE_LIMITED);
        FakeLlmProvider gemini = FakeLlmProvider.unconfigured("gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> java.util.Optional.empty());
        LlmAdminController controller = new LlmAdminController(chain);

        // exercise a failure so lastFailureClass is populated
        try {
            chain.generate(LlmRequest.of("system", "user"));
        } catch (LlmProviderException expected) {
            // gemini is unconfigured → the exhausted-chain throw is expected here
        }

        Map<String, Object> report = controller.chainHealth();
        assertThat(report).containsKeys("chainAvailable", "providers");
        assertThat((Map<String, LlmProviderHealth.Snapshot>) report.get("providers"))
                .containsKeys("groq", "gemini");

        LlmProviderHealth.Snapshot groqSnapshot =
                ((Map<String, LlmProviderHealth.Snapshot>) report.get("providers")).get("groq");
        assertThat(groqSnapshot.enabled()).isTrue();
        assertThat(groqSnapshot.configured()).isTrue();
        assertThat(groqSnapshot.requestsToday()).isEqualTo(1);
        assertThat(groqSnapshot.dailyBudget()).isEqualTo(50);
        assertThat(groqSnapshot.remainingLocalBudget()).isEqualTo(49);
        assertThat(groqSnapshot.lastFailureClass()).isEqualTo(LlmFailureClass.RATE_LIMITED);
        assertThat(groqSnapshot.lastErrorAt()).isNotNull();

        LlmProviderHealth.Snapshot geminiSnapshot =
                ((Map<String, LlmProviderHealth.Snapshot>) report.get("providers")).get("gemini");
        assertThat(geminiSnapshot.enabled()).isFalse();
        assertThat(geminiSnapshot.configured()).isFalse();
        assertThat(geminiSnapshot.healthy()).isFalse();
    }

    @Test
    @DisplayName("snapshot output never carries credential material")
    void snapshotsNeverCarrySecrets() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 10);
        groq.generate(LlmRequest.of("SECRET-SYSTEM", "SECRET-QUESTION"));
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> java.util.Optional.empty());
        LlmAdminController controller = new LlmAdminController(chain);

        String rendered = String.valueOf(controller.chainHealth());
        assertThat(rendered).doesNotContain("SECRET");           // no prompt content
        assertThat(rendered).doesNotContain("api-key", "apiKey", "authorization");
    }
}
