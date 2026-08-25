package com.household.manager.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.EntityState;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresencePollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Berlin"));
    private static final long PROBE_TIMEOUT_MS = 2000L;

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
    private ObjectMapper objectMapper;
    private PresencePollingService service;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        objectMapper = new ObjectMapper();
        service = new PresencePollingService(deviceRepository, userRepository, probe,
                monitor, evaluator, entityStateService, objectMapper, CLOCK, PROBE_TIMEOUT_MS);
        // Nicht jeder Test erreicht reportPersonState fuer Person 5 (z. B. UNKNOWN
        // oder ein frueherer Abbruch) bzw. ruft aggregateState ueberhaupt auf
        // (z. B. beim DB-Fehler) - deshalb gezielt lenient statt strict zu brechen.
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
        lenient().when(evaluator.aggregateState(any())).thenReturn(Optional.empty());
        // Kein Test dieser Klasse befasst sich mit verwaisten Personen, es sei denn er
        // stubt find() explizit anders - Default: keine gespeicherten Entitaeten.
        lenient().when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE))
                .thenReturn(List.of());
    }

    private EntityState personEntity(long userId, String state, Map<String, Object> attributes) {
        try {
            return EntityState.builder()
                    .entityId("binary_sensor.presence_" + userId + "_home")
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.PRESENCE)
                    .sourceRef(String.valueOf(userId))
                    .friendlyName("Person " + userId + " anwesend")
                    .state(state)
                    .attributes(objectMapper.writeValueAsString(attributes))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EntityState householdEntity(String state) {
        return EntityState.builder()
                .entityId("binary_sensor.presence_household")
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.PRESENCE)
                .sourceRef("household")
                .friendlyName("Jemand zu Hause")
                .state(state)
                .attributes("{\"deviceClass\":\"presence\"}")
                .build();
    }

    private PresenceDevice device(long id, boolean active) {
        return device(id, 5L, active);
    }

    private PresenceDevice device(long id, long userId, boolean active) {
        return PresenceDevice.builder().id(id).userId(userId).name("iPhone")
                .host("192.168.1.50").active(active).build();
    }

    private static boolean forUser(List<PresenceDevice> devices, long userId) {
        // Null-safe: wenn ein zweiter argThat-Stub fuer dieselbe Methode
        // registriert wird, prueft Mockito intern, ob dessen eigener
        // Registrierungs-Dummy-Aufruf (mit null-Platzhaltern) zufaellig einen
        // bereits vorhandenen Stub trifft - dabei kann dieses Praedikat kurz
        // mit devices == null aufgerufen werden.
        return devices != null && devices.stream().anyMatch(d -> d.getUserId().equals(userId));
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
        assertThat(update.attributes()).containsEntry("lastSeenAt", NOW.toString());
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
                monitor, evaluator, entityStateService, objectMapper, laterClock, PROBE_TIMEOUT_MS);
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));

        laterService.poll();

        assertThat(monitor.statusOf(1L)).isPresent();
        assertThat(monitor.statusOf(1L).orElseThrow().firstCheckedAt()).isEqualTo(later);
    }

    @Test
    void beideZustaendeWerdenAnAggregateStateUebergeben() {
        // Zwei Personen (zwei userIds) mit unterschiedlichem Zustand: die
        // heikelste Zusage dieses Tasks ist, dass BEIDE Zustaende tatsaechlich
        // in der an aggregateState uebergebenen Sammlung landen - eine
        // Regression, die z. B. eine leere Liste durchreicht, muss hier auffallen.
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, 5L, true), device(2, 6L, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 5L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 6L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.AWAY, null));
        when(userRepository.findById(6L)).thenReturn(Optional.of(
                AppUser.builder().id(6L).username("anna").displayName("Anna").passwordHash("x").build()));

        service.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PresenceEvaluator.PersonState>> statesCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(evaluator).aggregateState(statesCaptor.capture());
        assertThat(statesCaptor.getValue()).containsExactlyInAnyOrder(
                PresenceEvaluator.PersonState.PRESENT, PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void unknownPersonWirdNichtGemeldetAberInsAggregatAufgenommen() {
        // Person 6 ist UNKNOWN (frisch angelegtes Geraet in der Anlauf-Karenz):
        // sie bekommt keine eigene Entitaetsmeldung, ihr Zustand muss aber
        // trotzdem an aggregateState gehen - sonst saehe das Aggregat eine
        // Mischung, die es nie gab, und wuerde faelschlich "off" melden statt
        // (wie der echte Evaluator fuer [AWAY, UNKNOWN]) gar nichts.
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, 5L, true), device(2, 6L, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 5L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.AWAY, null));
        when(evaluator.evaluate(argThat(devices -> forUser(devices, 6L)), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));
        when(evaluator.aggregateState(argThat(states -> states.contains(PresenceEvaluator.PersonState.UNKNOWN))))
                .thenReturn(Optional.empty());

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("binary_sensor.presence_5_home");
        assertThat(captor.getValue().state()).isEqualTo("off");
        verify(evaluator).aggregateState(argThat(states -> states.containsAll(
                List.of(PresenceEvaluator.PersonState.AWAY, PresenceEvaluator.PersonState.UNKNOWN))));
    }

    @Test
    void auswertungsfehlerEinerPersonBleibtStillOhneMeldung() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenThrow(new RuntimeException("Auswertung kaputt"));

        service.poll();

        verifyNoInteractions(entityStateService);
        verify(evaluator, never()).aggregateState(any());
    }

    // -- Timeout-Klemmung (presence.probe-timeout-ms) --------------------------
    //
    // 0 bedeutet fuer Socket.connect "unendlich" (ein unerreichbares Handy
    // bloeckte einen der wenigen geteilten Scheduler-Threads minutenlang), und
    // ein negativer oder zu grosser Wert (der (int)-Cast in SocketPresenceProbe
    // liefe ueber) laesst JEDE Probe mit IllegalArgumentException scheitern -
    // SocketPresenceProbe schluckt das zu SILENT, jede Person meldete dauerhaft
    // "off", ohne einen einzigen Log-Eintrag oberhalb von debug. Ein falsch
    // konfigurierter Wert darf deshalb nicht durchgereicht, sondern muss auf
    // einen plausiblen Bereich geklemmt werden (Muster PresenceSettingsService:
    // defensiver Fallback statt Anwendungsabsturz).

    private Duration probedTimeout(long configuredProbeTimeoutMs) {
        PresencePollingService clampedService = new PresencePollingService(deviceRepository, userRepository,
                probe, monitor, evaluator, entityStateService, objectMapper, CLOCK, configuredProbeTimeoutMs);
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));

        clampedService.poll();

        ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
        verify(probe).probe(anyString(), anyList(), captor.capture());
        return captor.getValue();
    }

    @Test
    void nullWirdAufMindestwertGeklemmt() {
        assertThat(probedTimeout(0L)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void negativerWertWirdAufMindestwertGeklemmt() {
        assertThat(probedTimeout(-500L)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void zuGrosserWertWirdAufHoechstwertGeklemmt() {
        assertThat(probedTimeout(Long.MAX_VALUE)).isEqualTo(Duration.ofMillis(5000));
    }

    @Test
    void plausiblerWertBleibtUnveraendert() {
        assertThat(probedTimeout(1500L)).isEqualTo(Duration.ofMillis(1500));
    }

    // Grenzwerte exakt am Rand: ein vertauschtes Math.max/Math.min wuerde von den
    // beiden Tests oben nicht zuverlaessig entdeckt (manche vertauschten Werte
    // treffen zufaellig den erwarteten Wert), faellt hier aber garantiert auf -
    // siehe Rechnung am Javadoc von MAX_PROBE_TIMEOUT_MS.
    @Test
    void mindestwertBleibtUnveraendert() {
        assertThat(probedTimeout(100L)).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void hoechstwertBleibtUnveraendert() {
        assertThat(probedTimeout(5000L)).isEqualTo(Duration.ofMillis(5000));
    }

    // -- Verwaiste Personen (letzte Geraetezeile geloescht) ---------------------

    @Test
    void verwaistePersonOhneGeraeteWirdUnavailableMitErhaltenenAttributen() {
        // Keine Geraetezeile mehr fuer Person 5 - weder aktiv noch inaktiv.
        when(deviceRepository.findAll()).thenReturn(List.of());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("deviceClass", "presence");
        attributes.put("personUserId", 5);
        attributes.put("lastSeenAt", "2026-08-25T09:00:00Z");
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE))
                .thenReturn(List.of(personEntity(5, "on", attributes)));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("binary_sensor.presence_5_home");
        assertThat(update.domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(update.source()).isEqualTo(EntitySource.PRESENCE);
        assertThat(update.sourceRef()).isEqualTo("5");
        assertThat(update.state()).isEqualTo("unavailable");
        assertThat(update.attributes())
                .containsEntry("deviceClass", "presence")
                .containsEntry("personUserId", 5)
                .containsEntry("lastSeenAt", "2026-08-25T09:00:00Z");
    }

    @Test
    void verwaistePersonWirdNichtInsAggregatUebernommen() {
        // Person 5 hat noch ein aktives Geraet, Person 6 keine Geraetezeile mehr.
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, 5L, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        Map<String, Object> attributes = Map.of("deviceClass", "presence");
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE))
                .thenReturn(List.of(personEntity(6, "off", attributes)));

        service.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<PresenceEvaluator.PersonState>> statesCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(evaluator).aggregateState(statesCaptor.capture());
        assertThat(statesCaptor.getValue()).containsExactly(PresenceEvaluator.PersonState.PRESENT);

        // Person 6 wird trotzdem als unavailable gemeldet - nur eben nicht im Aggregat.
        verify(entityStateService).reportState(argThat(u -> u.entityId().equals("binary_sensor.presence_6_home")
                && u.state().equals("unavailable")));
    }

    @Test
    void haushaltsEntitaetWirdVonAufraeumenNichtAngefasst() {
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE))
                .thenReturn(List.of(householdEntity("on")));

        service.poll();

        // Keine Geraete -> kein regulaerer Bericht; die Haushalts-Entitaet hat
        // sourceRef "household" (keine Zahl) und darf vom Aufraeumen nicht als
        // verwaiste Person missverstanden werden.
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void personMitGeraetenWirdNichtFaelschlichAufgeraeumt() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, 5L, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        Map<String, Object> attributes = Map.of("deviceClass", "presence");
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE))
                .thenReturn(List.of(personEntity(5, "on", attributes)));

        service.poll();

        verify(entityStateService, never()).reportState(argThat(u -> "unavailable".equals(u.state())));
    }
}
