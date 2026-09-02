package com.syllabai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SyllabAI backend — the Java modular monolith (Master Spec §2.1, §3, ADR-012).
 *
 * <p>Modules are packages, not microservices. Each module owns its services, domain
 * objects, ports and persistence adapters; cross-module communication happens through
 * domain events and contracts (Master Spec §3 "Important boundary").</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SyllabAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyllabAiApplication.class, args);
    }
}
