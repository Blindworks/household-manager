package com.household.manager.communicator;

import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CommunicatorCardFactoryTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T11:00:00Z"), ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock
    private ThermostatService thermostatService;
    @Mock
    private TemperatureSeriesService temperatureSeriesService;
    @Mock
    private SwitchQueryService switchQueryService;
    @Mock
    private HouseModeQueryService houseModeQueryService;
    @Mock
    private NukiLockService nukiLockService;

    private CommunicatorCardFactory factory;

    @BeforeEach
    void setUp() {
        factory = new CommunicatorCardFactory(thermostatService, temperatureSeriesService, switchQueryService,
                houseModeQueryService, nukiLockService, CLOCK);
        lenient().when(thermostatService.list()).thenReturn(List.of(
                new ThermostatDto(3L, "Wohnzimmerfenster", true, 23.5, 21.0, ThermostatMode.MANUAL, true, false,
                        NOW.minusMinutes(1), "dirigera:3"),
                new ThermostatDto(4L, "Bad", false, null, null, ThermostatMode.MANUAL, false, false, null, null)));
        lenient().when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("dirigera:3", "Temperatur Wohnzimmer", "20.4", "48", NOW.minusMinutes(5)),
                reading("zigbee:7", "Keller", "12.0", null, NOW.minusHours(3))));
        lenient().when(switchQueryService.listSwitches(null)).thenReturn(List.of(
                SwitchResponse.builder().entityId("switch.flur").displayName("Flur").state("on").available(true)
                        .icon("lightbulb").powerWatts(9.0).build()));
        lenient().when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId("input_boolean.manual_nachtmodus").displayName("Nachtmodus")
                        .icon("bedtime").state("off").build()));
        lenient().when(nukiLockService.listLocks()).thenReturn(List.of(
                new NukiLockResponse(17L, "Haustür", "locked", "off", 80, false)));
    }

    private static CurrentTemperatureReading reading(String id, String name, String temp, String humidity,
                                                     LocalDateTime measuredAt) {
        return CurrentTemperatureReading.builder().sensorId(id).name(name)
                .temperature(new BigDecimal(temp))
                .humidity(humidity == null ? null : new BigDecimal(humidity))
                .measuredAt(measuredAt).build();
    }

    @Test
    void klimaKarteNimmtDenRaumfuehlerUndFindetDasThermostatDazu() {
        Card card = factory.build("show_climate", Map.of("sensorId", "dirigera:3", "title", "Wohnzimmer"));

        assertEquals("climate", card.type());
        assertEquals("Wohnzimmer", card.title());
        assertEquals(20.4, card.temperature());
        assertEquals(48.0, card.humidity());
        assertFalse(card.stale());
        assertEquals(3L, card.thermostatId());
        assertEquals(21.0, card.targetTemp());
        assertTrue(card.heating());
    }

    @Test
    void klimaKarteUeberThermostatNimmtDessenRaumfuehlerStattDesHeizkoerperwerts() {
        Card card = factory.build("show_climate", Map.of("thermostatId", 3));

        assertEquals(20.4, card.temperature());
        assertEquals("Temperatur Wohnzimmer", card.title());
    }

    @Test
    void fuehlerOhneThermostatZeigtNurDenWertUndAlteMessungIstVeraltet() {
        Card card = factory.build("show_climate", Map.of("sensorId", "zigbee:7"));

        assertEquals(12.0, card.temperature());
        assertNull(card.humidity());
        assertTrue(card.stale());
        assertNull(card.thermostatId());
    }

    @Test
    void offlineThermostatBekommtKeinenSollwertRegler() {
        Card card = factory.build("show_climate", Map.of("thermostatId", 4));

        assertNull(card.thermostatId());
        assertNull(card.temperature());
        assertTrue(card.stale());
    }

    @Test
    void unbekannteIdsSindFehlerUndKeineKarte() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build("show_climate", Map.of("thermostatId", 99)));
        assertThrows(IllegalArgumentException.class,
                () -> factory.build("show_climate", Map.of("sensorId", "zigbee:99")));
        assertThrows(IllegalArgumentException.class, () -> factory.build("show_climate", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> factory.build("show_switch", Map.of("entityId", "switch.erfunden")));
        assertThrows(IllegalArgumentException.class,
                () -> factory.build("show_mode", Map.of("entityId", "switch.flur")));
    }

    @Test
    void schalterModusUndSchlossTragenDenLiveZustand() {
        Card sw = factory.build("show_switch", Map.of("entityId", "switch.flur"));
        Card mode = factory.build("show_mode", Map.of("entityId", "input_boolean.manual_nachtmodus"));
        Card lock = factory.build("show_lock", Map.of());

        assertEquals("on", sw.state());
        assertEquals(9.0, sw.powerWatts());
        assertEquals("off", mode.state());
        assertEquals("Nachtmodus", mode.title());
        assertEquals(17L, lock.smartlockId());
        assertEquals("locked", lock.state());
    }

    @Test
    void nurGueltigeVorschlaegeBleibenUndHoechstensVier() {
        List<Map<String, Object>> raw = List.of(
                Map.of("label", "Auf 22°", "kind", "thermostat-target", "thermostatId", 3, "value", 22),
                Map.of("label", "Zu heiß", "kind", "thermostat-target", "thermostatId", 3, "value", 45),
                Map.of("label", "Krumm", "kind", "thermostat-target", "thermostatId", 3, "value", 21.3),
                Map.of("label", "Fremd", "kind", "thermostat-target", "thermostatId", 99, "value", 21),
                Map.of("label", "Flur aus", "kind", "switch", "entityId", "switch.flur", "state", "off"),
                Map.of("label", "Erfunden", "kind", "switch", "entityId", "switch.x", "state", "off"),
                Map.of("label", "Umschalten", "kind", "switch", "entityId", "switch.flur", "state", "toggle"),
                Map.of("label", "Nacht an", "kind", "mode", "entityId", "input_boolean.manual_nachtmodus",
                        "state", "on"),
                Map.of("label", "Unbekannt", "kind", "unlock"),
                Map.of("label", "Verlauf", "kind", "ask", "question", "Wie war die Temperatur heute?"),
                Map.of("label", "Zu viel", "kind", "ask", "question", "Noch eine Frage"));

        Card card = factory.build("show_climate", Map.of("sensorId", "dirigera:3", "suggestions", raw));

        assertEquals(List.of("Auf 22°", "Flur aus", "Nacht an", "Verlauf"),
                card.suggestions().stream().map(CommunicatorDtos.Suggestion::label).toList());
        assertEquals(22.0, card.suggestions().get(0).value());
    }

    @Test
    void infoKarteKuerztTextUndErsetztEinKaputtesSymbol() {
        Card card = factory.build("show_info", Map.of("icon", "<script>", "title", "Hinweis",
                "text", "x".repeat(900)));

        assertEquals("info", card.icon());
        assertEquals(400, card.text().length());
    }
}
