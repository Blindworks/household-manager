package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresenceStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    @Mock
    private PresenceDeviceRepository deviceRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceEvaluator evaluator;

    private PresenceMonitor monitor;
    private PresenceStatusService service;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        service = new PresenceStatusService(deviceRepository, userRepository, monitor,
                evaluator, CLOCK);
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
    }

    @Test
    void liefertPersonenMitZustandGeraetenUndLokalzeit() {
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        monitor.update(1L, true, NOW);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        when(evaluator.aggregateState(any())).thenReturn(Optional.of("on"));

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.householdState()).isEqualTo("on");
        assertThat(response.persons()).hasSize(1);
        PresenceDtos.PersonStatus person = response.persons().get(0);
        assertThat(person.userId()).isEqualTo(5L);
        assertThat(person.displayName()).isEqualTo("Benedikt");
        assertThat(person.state()).isEqualTo("on");
        assertThat(person.lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(person.devices()).hasSize(1);
        assertThat(person.devices().get(0).lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
    }

    @Test
    void unknownWirdAlsUnknownAusgewiesen() {
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.householdState()).isEqualTo("unknown");
        assertThat(response.persons().get(0).state()).isEqualTo("unknown");
        assertThat(response.persons().get(0).lastSeenAt()).isNull();
    }

    @Test
    void ohneGeraeteKeinePersonen() {
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.persons()).isEmpty();
        assertThat(response.householdState()).isEqualTo("unknown");
    }
}
