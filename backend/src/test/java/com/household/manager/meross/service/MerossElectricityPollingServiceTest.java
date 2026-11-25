package com.household.manager.meross.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.PowerHistoryRecorder;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.meross.config.MerossElectricityPollingProperties;
import com.household.manager.meross.dto.MerossElectricityReading;
import com.household.manager.meross.dto.MerossPlugPollResult;
import com.household.manager.meross.dto.MerossPlugPollResult.Reason;
import com.household.manager.meross.dto.MerossPlugPollResult.UnreachablePlug;
import com.household.manager.meross.exception.MerossException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.systemlog.SystemJobMonitor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerossElectricityPollingServiceTest {

    private static final String DEVICE_ID = "2112156531504590863548e1e9817420";
    private static final String SECOND_DEVICE_ID = "aabbccddeeff00112233445566778899";
    private static final String POWER_ID = "sensor.meross_" + DEVICE_ID + "_power";
    private static final String ONLINE_ID = "binary_sensor.meross_" + DEVICE_ID + "_online";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T15:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private SystemJobMonitor jobMonitor;
    @Mock
    private MerossDeviceService merossDeviceService;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private PowerHistoryRecorder powerHistoryRecorder;

    private MerossElectricityPollingProperties properties;
    private MerossElectricityPollingService service;

    @BeforeEach
    void setUp() {
        properties = new MerossElectricityPollingProperties();
        lenient().when(merossDeviceService.isConfigured()).thenReturn(true);
        lenient().when(entityStateService.find(EntityDomain.SENSOR, EntitySource.MEROSS)).thenReturn(List.of());
        service = new MerossElectricityPollingService(properties, merossDeviceService, entityStateService,
                jobMonitor, new EntityStateResponseMapper(new ObjectMapper()), powerHistoryRecorder, CLOCK);
    }

    private MerossElectricityReading reading(String deviceId, String name, String watts) {
        return new MerossElectricityReading(deviceId, name, new BigDecimal(watts),
                new BigDecimal("230.1"), new BigDecimal("5.432"));
    }

    private MerossPlugPollResult readings(MerossElectricityReading... readings) {
        return new MerossPlugPollResult(List.of(readings), List.of());
    }

    private MerossPlugPollResult unreachable(Reason reason) {
        return new MerossPlugPollResult(List.of(), List.of(new UnreachablePlug(DEVICE_ID, "Waschmaschine", reason)));
    }

    private void knownPowerSensor(String state, String attributes) {
        EntityState entity = EntityState.builder()
                .entityId(POWER_ID).domain(EntityDomain.SENSOR).source(EntitySource.MEROSS)
                .sourceRef(DEVICE_ID).friendlyName("Waschmaschine Leistung")
                .state(state).attributes(attributes).build();
        when(entityStateService.find(EntityDomain.SENSOR, EntitySource.MEROSS)).thenReturn(List.of(entity));
    }

    private List<EntityStateUpdate> reported() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.atLeast(0)).reportState(captor.capture());
        return captor.getAllValues();
    }

    private EntityStateUpdate reportedFor(String entityId) {
        return reported().stream().filter(u -> u.entityId().equals(entityId)).findFirst().orElse(null);
    }

    @Test
    void meldetLeistungAlsSensorEntitaet() {
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenReturn(readings(reading(DEVICE_ID, "Waschmaschine", "1234.6")));

        service.poll();

        EntityStateUpdate update = reportedFor(POWER_ID);
        assertThat(update.state()).isEqualTo("1234.6");
        assertThat(update.friendlyName()).isEqualTo("Waschmaschine Leistung");
        assertThat(update.attributes()).containsEntry("unit", "W").containsEntry("reachable", true);
        assertThat(reportedFor(ONLINE_ID).state()).isEqualTo("on");
        assertThat(reportedFor(ONLINE_ID).attributes()).containsEntry("deviceClass", "connectivity");
    }

    @Test
    void meldetJedeMessfaehigeSteckdose() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(readings(
                reading(DEVICE_ID, "Waschmaschine", "1234.6"),
                reading(SECOND_DEVICE_ID, "Spuelmaschine", "2.0")));

        service.poll();

        assertThat(reported()).extracting(EntityStateUpdate::entityId)
                .filteredOn(id -> id.startsWith("sensor."))
                .containsExactly(POWER_ID, "sensor.meross_" + SECOND_DEVICE_ID + "_power");
    }

    @Test
    void lesefehlerFuehrtNichtZuUnavailableReport() {
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenThrow(new MerossException("Cloud antwortet nicht"));

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void offlineSteckdoseBehaeltWertUndWirdAlsNichtErreichbarMarkiert() {
        knownPowerSensor("1234.6", "{\"unit\":\"W\",\"deviceClass\":\"power\"}");
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(unreachable(Reason.OFFLINE));

        service.poll();

        EntityStateUpdate power = reportedFor(POWER_ID);
        assertThat(power.state()).isEqualTo("1234.6");
        assertThat(power.attributes())
                .containsEntry("reachable", false)
                .containsEntry("deviceClass", "power")
                .containsEntry("unreachableSince", "2026-11-25T16:00");
        assertThat(reportedFor(ONLINE_ID).state()).isEqualTo("off");
        verify(powerHistoryRecorder).recordPoint(eq(POWER_ID), eq(LocalDateTime.of(2026, 11, 25, 16, 0)), isNull());
    }

    @Test
    void einzelnerAbruffehlerAendertNichts() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\"}");
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(unreachable(Reason.READ_FAILED));

        service.poll();
        service.poll();

        verify(entityStateService, never()).reportState(any());
        verifyNoInteractions(powerHistoryRecorder);
    }

    @Test
    void dritterAbruffehlerInFolgeMarkiertAlsNichtErreichbar() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\"}");
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(unreachable(Reason.READ_FAILED));

        service.poll();
        service.poll();
        service.poll();

        assertThat(reportedFor(POWER_ID).attributes()).containsEntry("reachable", false);
    }

    @Test
    void erfolgreicherAbrufSetztDieFehlerzaehlungZurueck() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\"}");
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenReturn(unreachable(Reason.READ_FAILED), unreachable(Reason.READ_FAILED),
                        readings(reading(DEVICE_ID, "Waschmaschine", "5.0")),
                        unreachable(Reason.READ_FAILED));

        for (int i = 0; i < 4; i++) {
            service.poll();
        }

        assertThat(reported()).filteredOn(u -> u.entityId().equals(POWER_ID))
                .allSatisfy(u -> assertThat(u.attributes()).containsEntry("reachable", true));
    }

    @Test
    void schonNichtErreichbarWirdNichtErneutMarkiert() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\",\"reachable\":false}");
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(unreachable(Reason.OFFLINE));

        service.poll();

        assertThat(reportedFor(POWER_ID)).isNull();
        assertThat(reportedFor(ONLINE_ID).state()).isEqualTo("off");
        verifyNoInteractions(powerHistoryRecorder);
    }

    @Test
    void steckdoseOhneBekanntenLeistungssensorWirdIgnoriert() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(unreachable(Reason.OFFLINE));

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void rueckkehrMitGleichemWertSchliesstDieLueckeDerHistorie() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\",\"reachable\":false}");
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenReturn(readings(reading(DEVICE_ID, "Waschmaschine", "5.0")));

        service.poll();

        verify(powerHistoryRecorder).recordPoint(eq(POWER_ID), any(), eq(5.0));
        assertThat(reportedFor(ONLINE_ID).state()).isEqualTo("on");
    }

    @Test
    void rueckkehrMitNeuemWertUeberlaesstDieHistorieDemZustandswechsel() {
        knownPowerSensor("5.0", "{\"deviceClass\":\"power\",\"reachable\":false}");
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenReturn(readings(reading(DEVICE_ID, "Waschmaschine", "800.0")));

        service.poll();

        verifyNoInteractions(powerHistoryRecorder);
    }

    @Test
    void ohneMessfaehigeSteckdoseWirdNichtsGemeldet() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(readings());

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void deaktiviertesPollingMeldetNichts() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(merossDeviceService, entityStateService);
    }

    @Test
    void poll_loginFails_reportsFailure() {
        when(merossDeviceService.readElectricityOfAllPlugs())
                .thenThrow(new MerossException("Login fehlgeschlagen"));

        service.poll();

        verify(jobMonitor).reportFailure(contains("Login"));
    }

    @Test
    void poll_oneDeviceFails_doesNotReportFailure() {
        when(merossDeviceService.readElectricityOfAllPlugs()).thenReturn(new MerossPlugPollResult(
                List.of(reading(DEVICE_ID, "Waschmaschine", "5.0")),
                List.of(new UnreachablePlug(SECOND_DEVICE_ID, "Spuelmaschine", Reason.OFFLINE))));

        service.poll();

        verifyNoInteractions(jobMonitor);
    }

    @Test
    void poll_disabled_reportsInactive() {
        properties.setEnabled(false);

        service.poll();

        verify(jobMonitor).reportInactive(anyString());
    }

    @Test
    void poll_notConfigured_reportsInactive() {
        when(merossDeviceService.isConfigured()).thenReturn(false);

        service.poll();

        verify(jobMonitor).reportInactive(anyString());
        verify(jobMonitor, never()).reportFailure(anyString());
        verifyNoInteractions(entityStateService);
    }
}
