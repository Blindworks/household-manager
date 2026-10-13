package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.calendar.CalendarEventService;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.dto.CalendarPersonView;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.PowerConsumerQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.network.NetworkDtos;
import com.household.manager.network.NetworkStatusService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.petsupply.PetSupplyDtos;
import com.household.manager.petsupply.PetSupplyService;
import com.household.manager.presence.PresenceDtos;
import com.household.manager.presence.PresenceStatusService;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.thermostat.ThermostatService;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import com.household.manager.zigbee.service.ZigbeeStreamMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class HouseSnapshotBuilderTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 14, 3);

    private PresenceStatusService presenceStatusService;
    private HouseModeQueryService houseModeQueryService;
    private ThermostatService thermostatService;
    private TemperatureSeriesService temperatureSeriesService;
    private EntityStateService entityStateService;
    private PowerConsumerQueryService powerConsumerQueryService;
    private SwitchQueryService switchQueryService;
    private PetSupplyService petSupplyService;
    private CalendarEventService calendarEventService;
    private NetworkStatusService networkStatusService;
    private ZigbeeStreamMonitor zigbeeStreamMonitor;
    private NukiLockService nukiLockService;
    private AgentProposalService proposalService;
    private AgentNoteService noteService;
    private AgentPolicyService policyService;
    private AgentActionDescriber describer;
    private final EntityStateResponseMapper mapper = new EntityStateResponseMapper(new ObjectMapper());

    @BeforeEach
    void setUp() {
        presenceStatusService = mock(PresenceStatusService.class);
        houseModeQueryService = mock(HouseModeQueryService.class);
        thermostatService = mock(ThermostatService.class);
        temperatureSeriesService = mock(TemperatureSeriesService.class);
        entityStateService = mock(EntityStateService.class);
        powerConsumerQueryService = mock(PowerConsumerQueryService.class);
        switchQueryService = mock(SwitchQueryService.class);
        lenient().when(switchQueryService.listSwitches(null)).thenReturn(List.of());
        petSupplyService = mock(PetSupplyService.class);
        calendarEventService = mock(CalendarEventService.class);
        networkStatusService = mock(NetworkStatusService.class);
        zigbeeStreamMonitor = mock(ZigbeeStreamMonitor.class);
        nukiLockService = mock(NukiLockService.class);
        proposalService = mock(AgentProposalService.class);
        noteService = mock(AgentNoteService.class);
        policyService = mock(AgentPolicyService.class);
        describer = mock(AgentActionDescriber.class);
        lenient().when(describer.describe(any())).thenReturn("Heizung Wohnzimmer auf 21,0 °C");

        lenient().when(presenceStatusService.getStatus())
                .thenReturn(new PresenceDtos.StatusResponse("unknown", List.of()));
        lenient().when(houseModeQueryService.listModes()).thenReturn(List.of());
        lenient().when(thermostatService.list()).thenReturn(List.of());
        lenient().when(temperatureSeriesService.getCurrent()).thenReturn(List.of());
        lenient().when(entityStateService.find(any(), any())).thenReturn(List.of());
        lenient().when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());
        lenient().when(powerConsumerQueryService.listConsumers(any())).thenReturn(List.of());
        lenient().when(petSupplyService.getSupplies()).thenReturn(List.of());
        lenient().when(calendarEventService.getUpcoming(anyInt())).thenReturn(List.of());
        lenient().when(networkStatusService.getStatus())
                .thenReturn(new NetworkDtos.StatusResponse(true, 12, true, NOW, null, List.of()));
        lenient().when(zigbeeStreamMonitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.OK, null, 0, null, null, List.of()));
        lenient().when(nukiLockService.listLocks()).thenReturn(List.of());
        lenient().when(proposalService.recentForPrompt()).thenReturn(List.of());
        lenient().when(noteService.renderForPrompt()).thenReturn("");
        lenient().when(policyService.all()).thenReturn(Map.of());
    }

    private HouseSnapshotBuilder builder() {
        Clock clock = Clock.fixed(ZonedDateTime.of(NOW, BERLIN).toInstant(), BERLIN);
        return new HouseSnapshotBuilder(clock, presenceStatusService, houseModeQueryService, thermostatService,
                temperatureSeriesService, entityStateService, mapper, powerConsumerQueryService, switchQueryService, petSupplyService,
                calendarEventService, networkStatusService, zigbeeStreamMonitor, nukiLockService, proposalService,
                noteService, policyService, describer);
    }

    private static EntityState contact(String entityId, String customName, String state, String attributes) {
        return EntityState.builder().entityId(entityId).domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.ZIGBEE).friendlyName("Kontakt").customName(customName)
                .state(state).attributes(attributes).lastChanged(NOW).lastUpdated(NOW).build();
    }

    @Test
    void failingSourceIsReportedNotThrown() {
        when(presenceStatusService.getStatus()).thenThrow(new RuntimeException("Tractive 401"));

        String s = builder().build();

        assertTrue(s.contains("Anwesenheit: nicht abrufbar (Tractive 401)"));
        assertTrue(s.contains("Modi:"));
    }

    @Test
    void timeSectionShowsGermanDateAndSunPhase() {
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.of(EntityState.builder()
                .entityId("sensor.sun").state("day").attributes("{\"sunset\":\"18:31\",\"sunrise\":\"07:20\"}")
                .build()));

        String s = builder().build();

        assertTrue(s.contains("Montag, 12.10.2026 14:03"), s);
        assertTrue(s.contains("Sonnenphase: day"), s);
        assertTrue(s.contains("18:31"), s);
    }

    @Test
    void presenceListsPersonsAndHousehold() {
        when(presenceStatusService.getStatus()).thenReturn(new PresenceDtos.StatusResponse("on", List.of(
                new PresenceDtos.PersonStatus(1L, "Benedikt", "on", LocalDateTime.of(2026, 10, 12, 13, 58), List.of()),
                new PresenceDtos.PersonStatus(2L, "Anna", "unknown", null, List.of()))));

        String s = builder().build();

        assertTrue(s.contains("Benedikt: zu Hause (zuletzt 13:58)"), s);
        assertTrue(s.contains("Anna: unbekannt"), s);
    }

    @Test
    void thermostatsShowValuesAndOffline() {
        when(thermostatService.list()).thenReturn(List.of(
                new ThermostatDto(3L, "Wohnzimmer", true, 18.5, 17.0,
                        ThermostatMode.MANUAL, false, false, LocalDateTime.of(2026, 10, 12, 14, 2)),
                new ThermostatDto(4L, "Bad", false, null, null, ThermostatMode.MANUAL, false, false, null)));

        String s = builder().build();

        assertTrue(s.contains("Wohnzimmer (id 3): ist 18,5 °C, soll 17,0 °C, MANUAL, heizt nicht, Stand 14:02"), s);
        assertTrue(s.contains("Bad (id 4): offline"), s);
    }

    @Test
    void modesAreListedWithState() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(
                new ModeResponse("input_boolean.manual_nacht", "Nachtmodus", "bedtime", "off"),
                new ModeResponse("input_boolean.manual_toni", "Toni allein", "pets", "on")));

        String s = builder().build();

        assertTrue(s.contains("Nachtmodus (input_boolean.manual_nacht): aus, Toni allein (input_boolean.manual_toni): an"), s);
    }

    @Test
    void freshTemperatureShowsAge() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(CurrentTemperatureReading.builder()
                .sensorId("zigbee:12").name("Wohnzimmer").temperature(new BigDecimal("20.1"))
                .humidity(new BigDecimal("48")).measuredAt(NOW.minusMinutes(3)).build()));

        String s = builder().build();

        assertTrue(s.contains("Wohnzimmer (sensorId zigbee:12): 20,1 °C / 48 %, vor 3 min"), s);
        assertFalse(s.contains("VERALTET"));
    }

    @Test
    void staleTemperatureIsMarked() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(CurrentTemperatureReading.builder()
                .sensorId("zigbee:12").name("Wohnzimmer").temperature(new BigDecimal("20.1"))
                .measuredAt(NOW.minusMinutes(90)).build()));

        assertTrue(builder().build().contains("VERALTET"));
    }

    @Test
    void openDoorContactsAreListedByName() {
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.ZIGBEE)).thenReturn(List.of(
                contact("binary_sensor.a", "Terrassentür", "on", "{\"deviceClass\":\"door\"}"),
                contact("binary_sensor.b", null, "off", "{\"deviceClass\":\"door\"}"),
                contact("binary_sensor.c", "Bewegung", "on", "{\"deviceClass\":\"motion\"}")));

        String s = builder().build();

        assertTrue(s.contains("Kontakte offen:\n  Terrassentür"), s);
        assertFalse(s.contains("Bewegung"));
    }

    @Test
    void noOpenContactsSaysKeine() {
        assertTrue(builder().build().contains("Kontakte offen: keine"));
    }

    @Test
    void unavailableContactIsReportedAsUnknownNotClosed() {
        when(entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.ZIGBEE)).thenReturn(List.of(
                contact("binary_sensor.a", "Terrassentür", "unavailable", "{\"deviceClass\":\"door\"}")));

        assertTrue(builder().build().contains("Terrassentür: unbekannt"));
    }

    @Test
    void consumersAreListed() {
        when(powerConsumerQueryService.listConsumers(10)).thenReturn(List.of(
                new PowerConsumerResponse("sensor.w", "Waschmaschine", new BigDecimal("540"), false)));

        assertTrue(builder().build().contains("Waschmaschine: 540 W"));
    }

    @Test
    void helpersExcludeModes() {
        EntityState helper = EntityState.builder().entityId("input_boolean.manual_wm").domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL).friendlyName("Waschmaschine läuft").state("on")
                .attributes("{}").lastChanged(LocalDateTime.of(2026, 10, 12, 13, 40)).build();
        EntityState mode = EntityState.builder().entityId("input_boolean.manual_nacht").domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL).friendlyName("Nachtmodus").state("off")
                .attributes("{\"mode\":true}").lastChanged(NOW).build();
        when(entityStateService.find(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)).thenReturn(List.of(helper, mode));

        String s = builder().build();

        assertTrue(s.contains("Waschmaschine läuft (input_boolean.manual_wm): an (seit 13:40)"), s);
        assertFalse(s.contains("Nachtmodus: aus (seit"), s);
    }

    @Test
    void suppliesCalendarNetworkZigbeeAndLocksAreRendered() {
        when(petSupplyService.getSupplies()).thenReturn(List.of(new PetSupplyDtos.SupplyResponse("toni_cans", "Futter",
                "Dosen", new BigDecimal("12"), new BigDecimal("48"), new BigDecimal("0.5"), BigDecimal.ONE, 25, 12)));
        when(calendarEventService.getUpcoming(5)).thenReturn(List.of(CalendarOccurrenceResponse.builder()
                .title("Tierarzt").occurrenceDate(LocalDate.of(2026, 10, 14)).startTime(LocalTime.of(9, 30))
                .persons(List.of(new CalendarPersonView(1L, "Benedikt"))).build()));
        when(zigbeeStreamMonitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.STILL, null, 20, null, null, List.of()));
        when(nukiLockService.listLocks()).thenReturn(List.of(
                new NukiLockResponse(1L, "Haustür", "locked", "closed", 80, false)));

        String s = builder().build();

        assertTrue(s.contains("Futter: 12 Dosen, reicht 12 Tage"), s);
        assertTrue(s.contains("14.10.2026 09:30 Tierarzt"), s);
        assertTrue(s.contains("Netzwerk:\n  online, Latenz 12 ms"), s);
        assertTrue(s.contains("Zigbee:\n  STILL"), s);
        assertTrue(s.contains("Haustür: locked, Tür closed"), s);
    }

    @Test
    void proposalsAreRenderedWithIdStatusAndReason() {
        AgentProposal p = AgentProposal.builder().id(17L).status(ProposalStatus.PENDING)
                .reason("kalt bei Anwesenheit").build();
        when(proposalService.recentForPrompt()).thenReturn(List.of(p));
        when(proposalService.actionOf(p)).thenReturn(new AgentAction(ActionClass.HEATING, "3",
                Map.of("targetTemp", 21.0), "kalt bei Anwesenheit", false));

        String s = builder().build();

        assertTrue(s.contains("#17 PENDING Heizung Wohnzimmer auf 21,0 °C — kalt bei Anwesenheit"), s);
    }

    @Test
    void policiesAndNotesAreIncluded() {
        when(policyService.all()).thenReturn(Map.of(ActionClass.HEATING, ActionPolicy.PROPOSE,
                ActionClass.SWITCH, ActionPolicy.DIRECT));
        when(noteService.renderForPrompt()).thenReturn("#1 [PREFERENCE, USER, unbefristet] 21 Grad reichen");

        String s = builder().build();

        assertTrue(s.contains("HEATING: nur vorschlagen"));
        assertTrue(s.contains("SWITCH: direkt"));
        assertTrue(s.contains("21 Grad reichen"));
    }

    private void manyTemperatureSensors() {
        List<CurrentTemperatureReading> readings = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            readings.add(CurrentTemperatureReading.builder().sensorId("zigbee:" + i).name("Sensor " + i)
                    .temperature(new BigDecimal("20.1")).measuredAt(NOW.minusMinutes(3)).build());
        }
        when(temperatureSeriesService.getCurrent()).thenReturn(readings);
    }

    @Test
    void snapshotIsCapped() {
        manyTemperatureSensors();

        String s = builder().build();

        assertTrue(s.length() <= 12_050);
        assertTrue(s.endsWith("… [gekürzt]"));
    }

    @Test
    void notesSurviveTheOverallCap() {
        manyTemperatureSensors();
        when(noteService.renderForPrompt()).thenReturn("#1 [PREFERENCE, USER, unbefristet] Nie unter 19 Grad");
        when(policyService.all()).thenReturn(Map.of(ActionClass.HEATING, ActionPolicy.PROPOSE));

        String s = builder().build();

        assertTrue(s.endsWith("… [gekürzt]"));
        assertTrue(s.contains("Nie unter 19 Grad"));
        assertTrue(s.contains("HEATING: nur vorschlagen"));
    }

    @Test
    void notesAreCappedOnTheirOwn() {
        when(noteService.renderForPrompt()).thenReturn("x".repeat(20000));

        String s = builder().build();

        assertTrue(s.contains("… [Notizen gekürzt]"), s);
        assertFalse(s.contains("x".repeat(4001)));
        assertTrue(s.contains("Heizung:"), "Zustandsabschnitte bleiben trotz langer Notizen erhalten");
    }

    @Test
    void proposalsAreCappedOnTheirOwn() {
        List<AgentProposal> proposals = new java.util.ArrayList<>();
        for (long i = 0; i < 100; i++) {
            AgentProposal p = AgentProposal.builder().id(i).status(ProposalStatus.REJECTED)
                    .actionClass(ActionClass.HEATING).target("3").reason("y".repeat(50)).build();
            proposals.add(p);
            when(proposalService.actionOf(p)).thenThrow(new RuntimeException("kaputt"));
        }
        when(proposalService.recentForPrompt()).thenReturn(proposals);

        String s = builder().build();

        assertTrue(s.contains("… [Vorschläge gekürzt]"), s);
    }

    @Test
    void sectionsStartWithBindingOnesAndEndWithProposals() {
        String s = builder().build();

        List<String> order = List.of("Zeit:", "Freigaben:", "Notizen:", "Anwesenheit:", "Modi:", "Heizung:",
                "Schalter:", "Temperaturen:", "Haustür:", "Offene und letzte Vorschläge (24 h):");
        int last = -1;
        for (String title : order) {
            int at = s.indexOf(title);
            assertTrue(at > last, title + " steht nicht an der erwarteten Stelle:\n" + s);
            last = at;
        }
    }

    @Test
    void switchSectionListsSwitchesWithIds() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(
                SwitchResponse.builder().entityId("switch.meross_wm").displayName("Waschmaschine").state("on")
                        .powerWatts(540.4).build(),
                SwitchResponse.builder().entityId("switch.kasa_50").displayName("Kamera").state("off").build(),
                SwitchResponse.builder().entityId("switch.tapo_9").displayName("Flur").state("unavailable").build()));

        String s = builder().build();

        assertTrue(s.contains("Schalter:\n  Waschmaschine (switch.meross_wm): an, 540 W"), s);
        assertTrue(s.contains("Kamera (switch.kasa_50): aus"), s);
        assertTrue(s.contains("Flur (switch.tapo_9): unbekannt"), s);
    }
}
