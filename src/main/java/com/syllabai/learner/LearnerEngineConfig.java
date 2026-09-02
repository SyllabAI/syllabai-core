package com.syllabai.learner;

import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.bkt.BktEngine;
import com.syllabai.learner.decay.EbbinghausDecayService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the pure domain engines as application beans (Master Spec §23:
 * domain classes stay framework-free; wiring lives in configuration).
 */
@Configuration
public class LearnerEngineConfig {

    @Bean
    BktEngine bktEngine() {
        return new BktEngine();
    }

    @Bean
    BdtEngine bdtEngine() {
        return new BdtEngine();
    }

    @Bean
    EbbinghausDecayService ebbinghausDecayService() {
        return new EbbinghausDecayService();
    }
}
