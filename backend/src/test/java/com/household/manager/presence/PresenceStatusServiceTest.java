package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        // Nur in "ohneGeraeteKeinePersonen" bleibt die Gruppierung leer und
        // displayNameOf(5L) wird nie aufgerufen - deshalb gezielt lenient statt
        // strict zu brechen (Muster PresencePollingServiceTest).
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
    }

    private static boolean forUser(List<PresenceDevice> devices, long userId) {
        return devices != null && devices.stream().anyMatch(d -> d.getUserId().equals(userId));
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

    @Test
    void beideZustaendeGehenAnAggregateStateUndReihenfolgeIstNachUserId() {
        // Zwei Personen mit unterschiedlichem Zustand, absichtlich in DB-Reihenfolge
        // userId 6 vor userId 3 zurueckgegeben: eine Mutation, die z. B. eine leere
        // oder einseitig gefilterte Sammlung an aggregateState durchreicht, oder die
        // die TreeMap-Gruppierung durch eine unsortierte Map ersetzt, bleibt sonst
        // unentdeckt gruen (Muster PresencePollingServiceTest).
        PresenceDevice deviceSix = PresenceDevice.builder().id(1L).userId(6L)
                .name("iPhone Anna").host("192.168.1.51").active(true).build();
        PresenceDevice deviceThree = PresenceDevice.builder().id(2L).userId(3L)
                .name("iPhone Toni").host("192.168.1.52").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(deviceSix, deviceThree));
        when(userRepository.findById(6L)).thenReturn(Optional.of(
                AppUser.builder().id(6L).username("anna").displayName("Anna").passwordHash("x").build()));
        when(userRepository.findById(3L)).thenReturn(Optional.of(
                AppUser.builder().id(3L).username("toni").displayName("Toni").passwordHash("x").build()));
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 6L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 3L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.AWAY, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.of("on"));

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.persons()).extracting(PresenceDtos.PersonStatus::userId)
                .containsExactly(3L, 6L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PresenceEvaluator.PersonState>> statesCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(evaluator).aggregateState(statesCaptor.capture());
        assertThat(statesCaptor.getValue()).containsExactlyInAnyOrder(
                PresenceEvaluator.PersonState.PRESENT, PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void geraetOhneMonitorEintragZeigtBeideZeitstempelAlsNull() {
        // Deaktiviertes Geraet: der Poller hat seinen Monitor-Eintrag entfernt
        // (PresencePollingService.poll), es gibt also keinen DeviceProbeStatus.
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(false).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNAVAILABLE, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());

        PresenceDtos.StatusResponse response = service.getStatus();

        PresenceDtos.DeviceStatusResponse deviceStatus = response.persons().get(0).devices().get(0);
        assertThat(deviceStatus.lastSeenAt()).isNull();
        assertThat(deviceStatus.lastCheckedAt()).isNull();
    }

    @Test
    void unbekannterBenutzerBekommtFallbackAnzeigenamen() {
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(99L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNAVAILABLE, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());
        // Kein Stub fuer findById(99L): unstubbte Optional-Rueckgabe ist bei
        // Mockito bereits Optional.empty(), genau der Fall "Benutzer existiert
        // nicht (mehr)".

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.persons().get(0).displayName()).isEqualTo("Person 99");
    }
}
