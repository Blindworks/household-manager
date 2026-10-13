package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorAvailabilityWatchdogTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T07:00:00Z"), BERLIN);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorRepository repository;
    @Mock private EntityStateService entityStateService;

    private BleSensorAvailabilityWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new BleSensorAvailabilityWatchdog(repository, new BleSensorEntityMapper(), entityStateService, CLOCK, 10);
    }

    private BleSensor sensor(LocalDateTime lastSeen) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true)
                .lastSeenAt(lastSeen).lastTemperature(new BigDecimal("21.5")).lastHumidity(new BigDecimal("50"))
                .lastBatteryMv(3030).build();
    }

    @Test
    void stillerSensorWirdUnavailableMitAttributen() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(11))));
        when(entityStateService.getByEntityId(anyString()))
                .thenReturn(Optional.of(EntityState.builder().state("21.5").build()));

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(u -> {
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsEntry("batteryPercent", 100).containsKey("unit");
        });
    }

    @Test
    void frischerSensorBleibtUnberuehrt() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(9))));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void bereitsUnavailableWirdNichtErneutGemeldet() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(60))));
        when(entityStateService.getByEntityId(anyString()))
                .thenReturn(Optional.of(EntityState.builder().state("unavailable").build()));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void sensorOhneJeEineMeldungWirdUebersprungen() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(null)));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void wirftNie() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("db weg"));

        watchdog.check();
    }
}
