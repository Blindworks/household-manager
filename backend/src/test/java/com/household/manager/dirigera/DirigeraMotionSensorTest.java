package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** MYGGSPRAY-Bewegungsmelder, gegen die am 2026-11-16 aufgezeichneten Fixtures. */
class DirigeraMotionSensorTest {

    private static final String RELATION = "54552662-7da3-4993-b17c-488b8dd1b5e0";
    private static final String HUB_ID = "05f1aba0-b770-479e-994a-f167e187ce32_1";
    private static final DirigeraHubSettings PAIRED = new DirigeraHubSettings("192.168.1.159", "tok", "abc", HUB_ID);

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T17:40:00Z"), ZoneId.of("Europe/Berlin"));
    private final List<DirigeraSensor> stored = new ArrayList<>();
    private DirigeraDeviceParser parser;
    private DirigeraHttpClient http;
    private DirigeraSensorRepository repository;
    private EntityStateService entityStates;
    private DirigeraPollingService service;

    @BeforeEach
    void setUp() {
        DirigeraSettingsService settings = mock(DirigeraSettingsService.class);
        http = mock(DirigeraHttpClient.class);
        parser = spy(new DirigeraDeviceParser(json));
        repository = mock(DirigeraSensorRepository.class);
        entityStates = mock(EntityStateService.class);
        service = new DirigeraPollingService(settings, http, parser, repository, new DirigeraEntityMapper(),
                mock(DirigeraReadingRecorder.class), new DirigeraButtonRegistry(), entityStates,
                mock(SystemJobMonitor.class), clock, true, 60);
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
    void geraetelisteFuehrtBelegungUndLichtsensorZuEinemBewegungsmelderZusammen() throws IOException {
        DirigeraDevices devices = parser.parse(fixture("myggspray-devices.json"));

        assertThat(devices.sensors()).singleElement().satisfies(s -> {
            assertThat(s.relationId()).isEqualTo(RELATION);
            assertThat(s.kind()).isEqualTo(DirigeraSensorKind.MOTION);
            assertThat(s.name()).isEqualTo("Bewegungssensor Treppenha");
            assertThat(s.room()).isEqualTo("Treppenhaus");
            assertThat(s.model()).isEqualTo("MYGGSPRAY wrlss mtn sensor");
            assertThat(s.detected()).isTrue();
            assertThat(s.illuminance()).isZero();
            assertThat(s.batteryPercent()).isEqualTo(100);
            assertThat(s.reachable()).isTrue();
            assertThat(s.open()).isNull();
            assertThat(s.temperature()).isNull();
        });
    }

    @Test
    void helligkeitWirdVomMatterRohwertInLuxUmgerechnet() {
        assertThat(DirigeraIlluminance.toLux(0)).isZero();
        assertThat(DirigeraIlluminance.toLux(1)).isEqualTo(1);
        assertThat(DirigeraIlluminance.toLux(15186)).isEqualTo(33);
        assertThat(DirigeraIlluminance.toLux(9031)).isEqualTo(8);
        assertThat(DirigeraIlluminance.toLux(40001)).isEqualTo(10000);
    }

    @Test
    void belegungsereignisWirdGelesen() throws IOException {
        Optional<DirigeraMotionChange> change = new DirigeraEventParser(json).parseMotionChange(fixture("motion-cleared-event.json"));

        assertThat(change).hasValueSatisfying(c -> {
            assertThat(c.key()).isEqualTo(RELATION + "_2");
            assertThat(c.detected()).isFalse();
            assertThat(c.illuminance()).isNull();
            assertThat(c.lastSeen()).isEqualTo(Instant.parse("2026-11-16T17:38:43Z"));
            assertThat(c.reachable()).isTrue();
        });
    }

    @Test
    void helligkeitsereignisWirdGelesenUndUmgerechnet() throws IOException {
        Optional<DirigeraMotionChange> change = new DirigeraEventParser(json).parseMotionChange(fixture("illuminance-event.json"));

        assertThat(change).hasValueSatisfying(c -> {
            assertThat(c.key()).isEqualTo(RELATION + "_1");
            assertThat(c.detected()).isNull();
            assertThat(c.illuminance()).isEqualTo(33);
        });
    }

    @Test
    void kontaktereignisIstKeinBewegungsereignis() throws IOException {
        DirigeraEventParser eventParser = new DirigeraEventParser(json);

        assertThat(eventParser.parseMotionChange(fixture("contact-open-event.json"))).isEmpty();
        assertThat(eventParser.parseContactChange(fixture("motion-cleared-event.json"))).isEmpty();
    }

    @Test
    void pollMeldetBelegungUndHelligkeit() throws IOException {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn(fixture("myggspray-devices.json"));

        service.poll();

        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getKind()).isEqualTo(DirigeraSensorKind.MOTION);
            assertThat(row.getLastDetected()).isTrue();
            assertThat(row.getLastIlluminance()).isZero();
        });
        List<EntityStateUpdate> updates = reported(2);
        assertThat(updates).extracting(EntityStateUpdate::entityId).containsExactly(
                "binary_sensor.dirigera_545526627da34993b17c488b8dd1b5e0_occupancy",
                "sensor.dirigera_545526627da34993b17c488b8dd1b5e0_illuminance");
        assertThat(updates.get(0).domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(updates.get(0).state()).isEqualTo("on");
        assertThat(updates.get(0).attributes()).containsEntry("deviceClass", "motion").containsEntry("room", "Treppenhaus");
        assertThat(updates.get(1).state()).isEqualTo("0");
        assertThat(updates.get(1).attributes()).containsEntry("unit", "lx").doesNotContainKey("deviceClass");
    }

    @Test
    void ereignisMitTeilgeraeteIdTrifftDieZeileDerRelationId() {
        stored.add(motionRow(true, 5));

        service.applyMotionChange(new DirigeraMotionChange(RELATION + "_2", false, null,
                Instant.parse("2026-11-16T17:38:43Z"), true));

        assertThat(stored.get(0).getLastDetected()).isFalse();
        assertThat(stored.get(0).getLastIlluminance()).isEqualTo(5);
        assertThat(stored.get(0).getLastSeenAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 18, 38, 43));
        assertThat(reported(2)).extracting(EntityStateUpdate::state).containsExactly("off", "5");
    }

    @Test
    void ereignisFuerUnbekanntenOderFremdenSensorWirdIgnoriert() {
        stored.add(DirigeraSensor.builder().relationId(RELATION).kind(DirigeraSensorKind.CONTACT)
                .knownToHub(true).reachable(true).build());

        service.applyMotionChange(new DirigeraMotionChange(RELATION + "_2", true, null, null, null));
        service.applyMotionChange(new DirigeraMotionChange("neu_2", true, null, null, null));

        verifyNoInteractions(entityStates);
    }

    @Test
    void ruhenderMelderBleibtVerfuegbarAuchWennLastSeenStundenAltIst() {
        when(http.getDevices(anyString(), anyString(), anyString())).thenReturn("x");
        doReturn(new DirigeraDevices(HUB_ID, List.of(new DirigeraSensorSnapshot(RELATION, DirigeraSensorKind.MOTION,
                "Melder", "Treppenhaus", "MYGGSPRAY", null, null, null, false, 2, 100, true,
                Instant.parse("2026-11-16T03:00:00Z"))), List.of())).when(parser).parse("x");

        service.poll();

        assertThat(reported(2)).extracting(EntityStateUpdate::state).containsExactly("off", "2");
    }

    @Test
    void pollMitAltemStandUeberschreibtFrischesBewegungsereignisNicht() {
        stored.add(motionRow(false, 5));
        when(http.getDevices(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            service.applyMotionChange(new DirigeraMotionChange(RELATION + "_2", true, null, null, null));
            service.applyMotionChange(new DirigeraMotionChange(RELATION + "_1", null, 40, null, null));
            return "x";
        });
        doReturn(new DirigeraDevices(HUB_ID, List.of(new DirigeraSensorSnapshot(RELATION, DirigeraSensorKind.MOTION,
                "Melder", "Treppenhaus", "MYGGSPRAY", null, null, null, false, 5, 100, true,
                Instant.parse("2026-11-16T17:39:00Z"))), List.of())).when(parser).parse("x");

        service.poll();

        assertThat(stored.get(0).getLastDetected()).isTrue();
        assertThat(stored.get(0).getLastIlluminance()).isEqualTo(40);
    }

    @Test
    void ohneBekanntenZustandWirdNichtsGeratenUndUnavailableGiltFuerBeide() {
        DirigeraSensor row = motionRow(null, null);
        DirigeraEntityMapper mapper = new DirigeraEntityMapper();

        assertThat(mapper.updates(row)).isEmpty();
        assertThat(mapper.unavailable(row)).extracting(EntityStateUpdate::state).containsOnly("unavailable").hasSize(2);
        assertThat(DirigeraEntityMapper.entityIds(row)).containsExactly(
                DirigeraEntityMapper.occupancyEntityId(RELATION), DirigeraEntityMapper.illuminanceEntityId(RELATION));
    }

    private List<EntityStateUpdate> reported(int count) {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates, times(count)).reportState(captor.capture());
        return captor.getAllValues();
    }

    private static DirigeraSensor motionRow(Boolean detected, Integer illuminance) {
        return DirigeraSensor.builder().relationId(RELATION).name("Melder").kind(DirigeraSensorKind.MOTION)
                .lastDetected(detected).lastIlluminance(illuminance).knownToHub(true).reachable(true)
                .lastSeenAt(LocalDateTime.of(2026, 11, 16, 18, 30)).build();
    }

    private static String fixture(String name) throws IOException {
        try (var in = DirigeraMotionSensorTest.class.getResourceAsStream("/dirigera/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
