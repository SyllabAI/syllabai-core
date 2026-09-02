package com.syllabai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chain observability for admins (Master Spec §32: AI latency/cost metrics,
 * §26.1 health and rate-budget tracking). RBAC: /api/v1/admin/** requires ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/llm")
public class LlmAdminController {

    private final FailoverLlmChain chain;

    public LlmAdminController(@Lazy FailoverLlmChain chain) {
        this.chain = chain;
    }

    @GetMapping("/chain-health")
    public Map<String, Object> chainHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chainAvailable", chain.available());
        report.put("providers", chain.memberHealth());
        return report;
    }
}
