package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dirigera.client.DirigeraCertificateMismatchException;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.dirigera.client.DirigeraUnauthorizedException;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DirigeraPollingServiceTest {

    private static final String HUB_ID = "05f1aba0-b770-479e-994a-f167e187ce32_1";
    private static final DirigeraHubSettings PAIRED = new DirigeraHubSettings("192.168.1.50", "tok", "abc", HUB_ID);

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("Europe/Berlin"));

    private DirigeraSettingsService settings;
    private DirigeraHttpClient http;
    private DirigeraDeviceParser parser;
    private DirigeraSensorRepository repository;
    private DirigeraReadingRecorder recorder;
    private EntityStateService entityStates;
    private SystemJobMonitor jobMonitor;
    private DirigeraPollingService service;
    private DirigeraButtonRegistry buttons;
    private final List<DirigeraSensor> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        settings = mock(DirigeraSettingsService.class);
        http = mock(DirigeraHttpClient.class);
        parser = spy(new DirigeraDeviceParser(new ObjectMapper()));
        repository = mock(DirigeraSensorRepository.class);
        recorder = mock(DirigeraReadingRecorder.class);
        entityStates = mock(EntityStateService.class);
        jobMonitor = mock(SystemJobMonitor.class);
        buttons = new DirigeraButtonRegistry();
        service = new DirigeraPollingService(settings, http, parser, repository, new DirigeraEntityMapper(),
                recorder, buttons, entityStates, jobMonitor, clock, true, 60);

        when(settings.get()).thenReturn(PAIRED);
        when(repository.findByRelationId(anyString())).thenAnswer(inv -> stored.stream()
                .filter(s -> s.getRelationId().equals(inv.getArgument(0))).findFirst());
        when(repository.findByKnownToHubTrue()).thenAnswer(inv -> stored.stream().filter(DirigeraSensor::isKnownToHub).toList());
        when(repository.save(any(DirigeraSensor.class))).thenAnswer(inv -> {
            DirigeraSensor s = inv.getArgument(0);
            if (!stored.contains(s)) {
                stored.add(s);
            }
            return s;
        });
        when(entityStates.getByEntityId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void nichtGekoppeltMeldetInactiveUndRuftHubNicht() {
        when(settings.get()).thenReturn(new DirigeraHubSettings(null, null, null, null));

        service.poll();

        verify(jobMonitor).reportInactive("nicht gekoppelt");
        verifyNoInteractions(http);
    }

    @Test
    void nichtGekoppeltMeldetBekannteEntitaetenGenauEinmalUnavailable() {
        stored.add(row("a", true));
        when(settings.get()).thenReturn(new DirigeraHubSettings(null, null, null, null));

        service.poll();

        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
        assertUnavailableReported();

        EntityState unavailable = EntityState.builder().state("unavailable").build();
        when(entityStates.getByEntityId(anyString())).thenReturn(Optional.of(unavailable));
        service.poll();

        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
    }

    @Test
    void kontaktWirdGespeichertUndAlsOffenGemeldetOhneVerlauf() {
        stubDevices(contactSnapshot("c", true));

        service.poll();

        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo(DirigeraSensorKind.CONTACT);
            assertThat(row.getLastOpen()).isTrue();
            assertThat(row.getLastTemperature()).isNull();
        });
        verifyNoInteractions(recorder);
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().entityId()).isEqualTo(DirigeraEntityMapper.contactEntityId("c"));
        assertThat(updates.getValue().state()).isEqualTo("on");
    }

    @Test
    void pollFuelltDieTasterListe() {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn("x");
        DirigeraButton top = new DirigeraButton("b_1", "Button", "Flur", "BILRESA", "top", 100);
        doReturn(new DirigeraDevices(HUB_ID, List.of(), List.of(top))).when(parser).parse("x");

        service.poll();

        assertThat(buttons.find("b_1")).contains(top);
    }

    @Test
    void kontaktEreignisMeldetSofort() {
        stored.add(contactRow("c", false));

        service.applyContactChange(new DirigeraContactChange("c", true, Instant.parse("2026-10-30T08:59:30Z"), true));

        assertThat(stored.get(0).getLastOpen()).isTrue();
        assertThat(stored.get(0).getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 10, 30, 9, 59, 30));
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().entityId()).isEqualTo(DirigeraEntityMapper.contactEntityId("c"));
        assertThat(updates.getValue().state()).isEqualTo("on");
    }

    @Test
    void ereignisFuerUnbekanntenSensorWirdIgnoriert() {
        stored.add(row("t", true));

        service.applyContactChange(new DirigeraContactChange("t", true, null, null));
        service.applyContactChange(new DirigeraContactChange("neu", true, null, null));

        verify(repository, never()).save(any(DirigeraSensor.class));
        verifyNoInteractions(entityStates);
    }

    @Test
    void ereignisFuerUnerreichbarenSensorWirdGespeichertAberNichtGemeldet() {
        DirigeraSensor row = contactRow("c", false);
        row.setReachable(false);
        stored.add(row);

        service.applyContactChange(new DirigeraContactChange("c", true, null, null));

        assertThat(row.getLastOpen()).isTrue();
        verifyNoInteractions(entityStates);
    }

    @Test
    void ereignisFehlerWirftNicht() {
        when(repository.findByRelationId("c")).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> service.applyContactChange(new DirigeraContactChange("c", true, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void pollMitAltemStandUeberschreibtFrischesEreignisNicht() {
        stored.add(contactRow("c", false));
        // Waehrend der Abruf laeuft, kommt "offen" ueber den Ereignisstrom; die Geraeteliste traegt noch "zu"
        when(http.getDevices(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            service.applyContactChange(new DirigeraContactChange("c", true, null, null));
            return "x";
        });
        doReturn(new DirigeraDevices(HUB_ID, List.of(contactSnapshot("c", false)), List.of())).when(parser).parse("x");

        service.poll();

        assertThat(stored.get(0).getLastOpen()).isTrue();
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates, times(2)).reportState(updates.capture());
        assertThat(updates.getAllValues()).extracting(EntityStateUpdate::state).containsOnly("on");
    }

    @Test
    void spaetererPollUebernimmtDenStandWiederVomHub() {
        MutableInstantClock moving = new MutableInstantClock(Instant.parse("2026-10-30T09:00:00Z"));
        service = new DirigeraPollingService(settings, http, parser, repository, new DirigeraEntityMapper(),
                recorder, buttons, entityStates, jobMonitor, moving, true, 60);
        stored.add(contactRow("c", false));
        service.applyContactChange(new DirigeraContactChange("c", true, null, null));
        moving.now = moving.now.plusSeconds(60);
        stubDevices(contactSnapshot("c", false));

        service.poll();

        assertThat(stored.get(0).getLastOpen()).isFalse();
    }

    @Test
    void verschwundenerKontaktWirdUnavailable() {
        stored.add(DirigeraSensor.builder().relationId("c").name("Badfenster")
                .kind(DirigeraSensorKind.CONTACT).lastOpen(false).knownToHub(true).build());
        stubDevices();
        when(entityStates.getByEntityId(anyString())).thenReturn(Optional.empty());

        service.poll();

        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().entityId()).isEqualTo(DirigeraEntityMapper.contactEntityId("c"));
        assertThat(updates.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void langerNameRaumUndModellWerdenAuf100ZeichenGekuerzt() {
        String lang = "x".repeat(150);
        stubDevices(new DirigeraSensorSnapshot("a", DirigeraSensorKind.ENVIRONMENT, lang, lang, lang,
                new BigDecimal("21.50"), new BigDecimal("40.00"), null, 90, true, Instant.parse("2026-10-30T08:59:00Z")));

        service.poll();

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getName()).hasSize(100);
        assertThat(stored.get(0).getRoom()).hasSize(100);
        assertThat(stored.get(0).getModel()).hasSize(100);
    }

    @Test
    void onUnpairedVerwirftFehlerUndMeldetUnavailable() {
        stored.add(row("a", true));
        when(http.getDevices(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("kaputt"));
        service.poll();
        assertThat(service.lastStatus().lastError()).isEqualTo("kaputt");

        service.onUnpaired();

        assertThat(service.lastStatus().lastError()).isNull();
        assertThat(service.lastStatus().lastSuccessAt()).isNull();
    }

    @Test
    void fixtureLegtVierZeilenAnUndMeldetAchtEntitaeten() throws IOException {
        when(http.getDevices("192.168.1.50", "abc", "tok")).thenReturn(fixture());

        service.poll();

        assertThat(stored).hasSize(4);
        verify(entityStates, times(8)).reportState(any(EntityStateUpdate.class));
        verify(recorder, times(4)).record(any(DirigeraSensor.class), any(LocalDateTime.class));
        verify(jobMonitor, never()).reportFailure(anyString());
        assertThat(service.lastStatus().lastSuccessAt()).isEqualTo(Instant.parse("2026-10-30T09:00:00Z"));
        assertThat(service.lastStatus().lastError()).isNull();
    }

    @Test
    void lastSeenWirdInClockZoneUmgerechnet() throws IOException {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn(fixture());

        service.poll();

        DirigeraSensor ess = stored.stream()
                .filter(s -> s.getRelationId().equals("dcc8d385-781d-4071-adf8-5f403b0cb997")).findFirst().orElseThrow();
        // 08:53:36Z ist in Berlin (Sommerzeit bis 25.10. vorbei: MEZ, UTC+1) 09:53:36
        assertThat(ess.getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 10, 30, 9, 53, 36));
    }

    @Test
    void zertifikatsfehlerMeldetFailureUndUnavailable() {
        stored.add(row("a", true));
        when(http.getDevices(anyString(), anyString(), anyString()))
                .thenThrow(new DirigeraCertificateMismatchException("anderes Zertifikat", null));

        service.poll();

        verify(jobMonitor).reportFailure("Zertifikat geändert – neu koppeln");
        assertThat(service.lastStatus().lastError()).isEqualTo("Zertifikat geändert – neu koppeln");
        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
        assertUnavailableReported();
    }

    @Test
    void tokenWiderrufenMeldetFailureMitNeuKoppeln() {
        stored.add(row("a", true));
        when(http.getDevices(anyString(), anyString(), anyString()))
                .thenThrow(new DirigeraUnauthorizedException("401"));

        service.poll();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(jobMonitor).reportFailure(reason.capture());
        assertThat(reason.getValue()).contains("neu koppeln");
        assertUnavailableReported();
    }

    @Test
    void fremdeGatewayIdSchreibtNichts() throws IOException {
        when(settings.get()).thenReturn(new DirigeraHubSettings("192.168.1.50", "tok", "abc", "anderer-hub"));
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn(fixture());

        service.poll();

        verify(repository, never()).save(any(DirigeraSensor.class));
        verify(jobMonitor).reportFailure("Anderes Gerät unter der Hub-Adresse");
    }

    @Test
    void alterLastSeenMeldetUnavailableGenauEinmal() {
        stubDevices(snapshot("a", true, Instant.parse("2026-10-30T06:00:00Z")));

        service.poll();
        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
        assertUnavailableReported();

        // Entitaeten stehen jetzt schon auf unavailable
        EntityState unavailable = EntityState.builder().state("unavailable").build();
        when(entityStates.getByEntityId(anyString())).thenReturn(Optional.of(unavailable));
        service.poll();

        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
        verify(recorder, never()).record(any(), any());
    }

    @Test
    void unreachableMeldetUnavailable() {
        stubDevices(snapshot("a", false, Instant.parse("2026-10-30T08:59:00Z")));

        service.poll();

        assertUnavailableReported();
        verify(recorder, never()).record(any(), any());
    }

    @Test
    void verschwundenerSensorWirdKnownToHubFalse() {
        DirigeraSensor gone = row("weg", true);
        stored.add(gone);
        stubDevices(snapshot("da", true, Instant.parse("2026-10-30T08:59:00Z")));

        service.poll();

        assertThat(gone.isKnownToHub()).isFalse();
        verify(repository).save(gone);
        verify(entityStates, times(2 + 2)).reportState(any(EntityStateUpdate.class));
    }

    @Test
    void fehlerBeiEinemSensorStopptDieAnderenNicht() {
        stubDevices(snapshot("kaputt", true, Instant.parse("2026-10-30T08:59:00Z")),
                snapshot("heil", true, Instant.parse("2026-10-30T08:59:00Z")));
        when(repository.findByRelationId("kaputt")).thenThrow(new IllegalStateException("db"));

        service.poll();

        assertThat(stored).extracting(DirigeraSensor::getRelationId).containsExactly("heil");
        verify(entityStates, times(2)).reportState(any(EntityStateUpdate.class));
    }

    @Test
    void pollWirftNie() {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn("x");
        doReturn(new DirigeraDevices(HUB_ID, List.of(), List.of())).when(parser).parse("x");
        when(repository.findByKnownToHubTrue()).thenThrow(new IllegalStateException("db weg"));

        assertThatCode(service::poll).doesNotThrowAnyException();
        verify(jobMonitor).reportFailure("db weg");
    }

    private void stubDevices(DirigeraSensorSnapshot... snapshots) {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn("x");
        doReturn(new DirigeraDevices(HUB_ID, List.of(snapshots), List.of())).when(parser).parse("x");
    }

    private void assertUnavailableReported() {
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates, org.mockito.Mockito.atLeastOnce()).reportState(updates.capture());
        assertThat(updates.getAllValues()).extracting(EntityStateUpdate::state).containsOnly("unavailable");
    }

    @Test
    void ruhenderKontaktBleibtVerfuegbarAuchWennLastSeenStundenAltIst() {
        // MYGGBETT aktualisiert lastSeen nur bei einem Auf/Zu (real 2026-11-04: 12:08, um 17:15 unveraendert)
        stubDevices(new DirigeraSensorSnapshot("c", DirigeraSensorKind.CONTACT, "Badfenster", "Bad",
                "MYGGBETT", null, null, false, 100, true, Instant.parse("2026-10-30T03:00:00Z")));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().state()).isEqualTo("off");
    }

    @Test
    void nichtErreichbarerKontaktWirdWeiterhinUnavailable() {
        stubDevices(new DirigeraSensorSnapshot("c", DirigeraSensorKind.CONTACT, "Badfenster", "Bad",
                "MYGGBETT", null, null, false, 100, false, Instant.parse("2026-10-30T08:59:00Z")));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void ereignisOhneLastSeenWirdBeiAltemLastSeenTrotzdemGemeldet() {
        stored.add(DirigeraSensor.builder().relationId("c").name("Badfenster").kind(DirigeraSensorKind.CONTACT)
                .lastOpen(false).knownToHub(true).reachable(true)
                .lastSeenAt(LocalDateTime.of(2026, 10, 30, 4, 0)).build());

        service.applyContactChange(new DirigeraContactChange("c", true, null, null));

        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates).reportState(updates.capture());
        assertThat(updates.getValue().state()).isEqualTo("on");
    }

    @Test
    void stillesThermometerWirdWeiterhinUnavailable() {
        stubDevices(snapshot("a", true, Instant.parse("2026-10-30T03:00:00Z")));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates, org.mockito.Mockito.atLeastOnce()).reportState(updates.capture());
        assertThat(updates.getAllValues()).extracting(EntityStateUpdate::state).containsOnly("unavailable");
    }

    private static DirigeraSensorSnapshot snapshot(String relationId, boolean reachable, Instant lastSeen) {
        return new DirigeraSensorSnapshot(relationId, DirigeraSensorKind.ENVIRONMENT, "Name " + relationId, "Raum",
                "TIMMERFLOTTE", new BigDecimal("21.50"), new BigDecimal("40.00"), null, 90, reachable, lastSeen);
    }

    private static DirigeraSensorSnapshot contactSnapshot(String relationId, Boolean open) {
        return new DirigeraSensorSnapshot(relationId, DirigeraSensorKind.CONTACT, "Badfenster", "Bad",
                "PARASOLL", null, null, open, 80, true, Instant.parse("2026-10-30T08:59:00Z"));
    }

    private static DirigeraSensor contactRow(String relationId, boolean open) {
        return DirigeraSensor.builder().relationId(relationId).name("Badfenster").kind(DirigeraSensorKind.CONTACT)
                .lastOpen(open).knownToHub(true).reachable(true)
                .lastSeenAt(LocalDateTime.of(2026, 10, 30, 9, 59)).build();
    }

    private static final class MutableInstantClock extends Clock {
        private Instant now;

        MutableInstantClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DirigeraSensor row(String relationId, boolean knownToHub) {
        return DirigeraSensor.builder().relationId(relationId).name("Name").knownToHub(knownToHub)
                .lastTemperature(new BigDecimal("20")).lastHumidity(new BigDecimal("50")).build();
    }

    private static String fixture() throws IOException {
        try (var in = DirigeraPollingServiceTest.class.getResourceAsStream("/dirigera/devices.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
