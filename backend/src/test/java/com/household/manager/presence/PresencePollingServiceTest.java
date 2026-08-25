package com.household.manager.presence;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresencePollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Berlin"));

    @Mock
    private PresenceDeviceRepository deviceRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceProbe probe;
    @Mock
    private PresenceEvaluator evaluator;
    @Mock
    private EntityStateService entityStateService;

    private PresenceMonitor monitor;
    private PresencePollingService service;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        service = new PresencePollingService(deviceRepository, userRepository, probe,
                monitor, evaluator, entityStateService, CLOCK);
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
        lenient().when(evaluator.aggregateState(any())).thenReturn(Optional.empty());
    }

    private PresenceDevice device(long id, boolean active) {
        return PresenceDevice.builder().id(id).userId(5L).name("iPhone")
                .host("192.168.1.50").active(active).build();
    }

    @Test
    void meldetAnwesendeSofortMitAttributen() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("binary_sensor.presence_5_home");
        assertThat(update.domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(update.source()).isEqualTo(EntitySource.PRESENCE);
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.friendlyName()).isEqualTo("Benedikt anwesend");
        assertThat(update.attributes()).containsEntry("deviceClass", "presence");
        assertThat(update.attributes()).containsEntry("personUserId", 5L);
        assertThat(update.attributes()).containsKey("lastSeenAt");
    }

    @Test
    void unknownMeldetNichts() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void aggregatWirdGemeldet() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.AWAY, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.of("off"));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate household = captor.getAllValues().stream()
                .filter(u -> u.entityId().equals("binary_sensor.presence_household"))
                .findFirst().orElseThrow();
        assertThat(household.state()).isEqualTo("off");
        assertThat(household.friendlyName()).isEqualTo("Jemand zu Hause");
    }

    @Test
    void deaktivierteGeraeteWerdenNichtGeprobt() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, false)));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNAVAILABLE, null));

        service.poll();

        verify(probe, never()).probe(anyString(), anyList(), any());
    }

    @Test
    void dbFehlerUeberspringtDenZyklusOhneZuWerfen() {
        when(deviceRepository.findAll()).thenThrow(new RuntimeException("DB weg"));

        service.poll();

        verifyNoInteractions(entityStateService);
    }

    @Test
    void reaktiviertesGeraetDurchlaeuftProbezeitErneut() {
        // Zyklus 1 (t = NOW): Geraet aktiv und wird geprobt -> Monitor bekommt
        // einen Eintrag mit firstCheckedAt = NOW.
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));

        service.poll();

        assertThat(monitor.statusOf(1L)).isPresent();
        assertThat(monitor.statusOf(1L).orElseThrow().firstCheckedAt()).isEqualTo(NOW);

        // Zyklus 2 (t = NOW, Geraet inzwischen deaktiviert): der Poller darf das
        // Geraet nicht mehr probieren UND muss seinen Monitor-Eintrag vergessen -
        // eine spaetere Reaktivierung soll keine "alte" firstCheckedAt erben.
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, false)));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNAVAILABLE, null));

        service.poll();

        assertThat(monitor.statusOf(1L)).isEmpty();

        // Zyklus 3 (t = NOW + 1h, Geraet reaktiviert): frische Probezeit ab dem
        // Zeitpunkt dieses Polls, nicht ab der laengst vergangenen ersten Sichtung.
        Instant later = NOW.plusSeconds(3600);
        Clock laterClock = Clock.fixed(later, ZoneId.of("Europe/Berlin"));
        PresencePollingService laterService = new PresencePollingService(deviceRepository, userRepository, probe,
                monitor, evaluator, entityStateService, laterClock);
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));

        laterService.poll();

        assertThat(monitor.statusOf(1L)).isPresent();
        assertThat(monitor.statusOf(1L).orElseThrow().firstCheckedAt()).isEqualTo(later);
    }
}
