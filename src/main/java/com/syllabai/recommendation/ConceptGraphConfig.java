package com.syllabai.recommendation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the settled T-C11 concept dependency graph into the recommendation
 * path: exactly one immutable {@link ConceptDependencyGraph} bean, loaded from
 * the SHA-256-pinned packaged snapshot at startup
 * ({@link ConceptDependencyGraphLoader#load()}) — a versioned, read-only
 * dependency like any other, never a second graph database and never a new
 * persistence model. The bean is consumed by {@link NextBestActionService}
 * as a candidate-nominating dependency layer only.
 */
@Configuration(proxyBeanMethods = false)
class ConceptGraphConfig {

    @Bean
    ConceptDependencyGraph conceptDependencyGraph(ConceptDependencyGraphLoader loader) {
        return loader.load();
    }
}
