package com.household.manager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Stellt eine {@link Clock} als Bean bereit, damit zeitabhaengige Services testbar sind,
 * ohne auf statische Aufrufe wie {@code LocalDate.now()} angewiesen zu sein.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
