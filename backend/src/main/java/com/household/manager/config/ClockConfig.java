package com.household.manager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Stellt eine {@link Clock} als Bean bereit, damit zeitabhaengige Services testbar sind,
 * ohne auf statische Aufrufe wie {@code LocalDate.now()} angewiesen zu sein.
 */
@Configuration
public class ClockConfig {

    /**
     * Zone bewusst festgenagelt statt {@code systemDefaultZone()}: Das Backend-Image
     * (eclipse-temurin) setzt kein TZ, und docker-compose gibt es nur zigbee2mqtt mit,
     * nicht dem Backend — im Container liefe die Uhr also auf UTC. Fuer einen Haushalt in
     * Deutschland heisst das: "heute" kippt zwei Stunden zu frueh, und die Abend-Durchsage
     * um 19:00 kaeme erst um 21:00 Ortszeit.
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Europe/Berlin"));
    }
}
