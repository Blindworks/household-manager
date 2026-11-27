package com.household.manager.tesla;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haelt fest, dass Spring den Client mit zwei Konstruktoren anlegen kann
 * (der Start in PROD scheiterte am 2026-11-27 an "No default constructor found").
 */
class TessieApiClientWiringTest {

    @Test
    void springLegtDenClientUeberDenOeffentlichenKonstruktorAn() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(TeslaProperties.class)
                .withBean(TessieApiClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TessieApiClient.class);
                });
    }

    @Test
    void springLegtDenBilddienstUeberDenOeffentlichenKonstruktorAn() {
        new ApplicationContextRunner()
                .withBean(TeslaSettingsService.class, () -> org.mockito.Mockito.mock(TeslaSettingsService.class))
                .withBean(TeslaVehicleService.class, () -> org.mockito.Mockito.mock(TeslaVehicleService.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(TeslaVehicleImageService.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TeslaVehicleImageService.class));
    }
}
