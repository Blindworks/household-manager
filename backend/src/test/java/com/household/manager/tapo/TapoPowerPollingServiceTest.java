package com.household.manager.tapo;

import com.household.manager.systemlog.SystemJobMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TapoPowerPollingServiceTest {

    private static final String LAUFBAND_ID = "8022ABCDEF0123456789";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SystemJobMonitor jobMonitor;
    @Mock
    private SmartDeviceRepository smartDeviceRepository;
    @Mock
    private TapoDeviceService tapoDeviceService;
    @Mock
    private EntityStateService entityStateService;

    private TapoPowerPollingService service;

    @BeforeEach
    void setUp() {
        service = new TapoPowerPollingService(smartDeviceRepository, tapoDeviceService, entityStateService, jobMonitor, true);
    }

    private SmartDevice device(String id, String name, String model, boolean online, String ip) {
        return SmartDevice.builder()
                .deviceType(DeviceType.TAPO)
                .externalDeviceId(id)
                .deviceName(name)
                .model(model)
                .isOnline(online)
                .ipAddress(ip)
                .build();
    }

    @Test
    void meldetLeistungDerP110AlsVerbraucherSensor() throws Exception {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO))
                .thenReturn(List.of(device(LAUFBAND_ID, "Laufband", "P110", true, "192.168.1.50")));
        when(tapoDeviceService.getCurrentPower(eq(LAUFBAND_ID), eq("192.168.1.50"), isNull()))
                .thenReturn(objectMapper.readTree("{\"current_power\": 412}"));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.tapo_" + LAUFBAND_ID.toLowerCase() + "_power");
        assertThat(update.source()).isEqualTo(EntitySource.TAPO);
        assertThat(update.sourceRef()).isEqualTo(LAUFBAND_ID);
        assertThat(update.state()).isEqualTo("412");
        assertThat(update.friendlyName()).isEqualTo("Laufband Leistung");
        assertThat(update.attributes()).containsEntry("deviceClass", "power").containsEntry("unit", "W");
    }

    @Test
    void fragtNurMessfaehigeErreichbareSteckdosenAb() {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO)).thenReturn(List.of(
                device("lampe", "Flur", "L530", true, "192.168.1.114"),
                device("aus", "Stern", "P110", false, "192.168.1.60"),
                device("ohne-ip", "Router", "P110", true, null)));

        service.poll();

        verifyNoInteractions(tapoDeviceService, entityStateService);
    }

    @Test
    void einLesefehlerMeldetKeinUnavailableUndStopptDieAnderenNicht() throws Exception {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO)).thenReturn(List.of(
                device("kaputt", "Router", "P110", true, "192.168.1.61"),
                device(LAUFBAND_ID, "Laufband", "P110(EU)", true, "192.168.1.50")));
        when(tapoDeviceService.getCurrentPower(eq("kaputt"), any(), any()))
                .thenThrow(new TapoException("nicht erreichbar"));
        when(tapoDeviceService.getCurrentPower(eq(LAUFBAND_ID), any(), any()))
                .thenReturn(objectMapper.readTree("{\"current_power\": 0}"));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().sourceRef()).isEqualTo(LAUFBAND_ID);
        assertThat(captor.getValue().state()).isEqualTo("0");
    }

    @Test
    void ohneCurrentPowerWirdNichtsGemeldet() throws Exception {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO))
                .thenReturn(List.of(device(LAUFBAND_ID, "Laufband", "P110", true, "192.168.1.50")));
        when(tapoDeviceService.getCurrentPower(any(), any(), any()))
                .thenReturn(objectMapper.readTree("{}"));

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void abgeschaltetTutNichts() {
        new TapoPowerPollingService(smartDeviceRepository, tapoDeviceService, entityStateService, jobMonitor, false).poll();

        verifyNoInteractions(smartDeviceRepository, tapoDeviceService, entityStateService);
    }

    @Test
    void erkenntMessfaehigeModelle() {
        assertThat(TapoPowerPollingService.isMeteringModel("P110")).isTrue();
        assertThat(TapoPowerPollingService.isMeteringModel("p115(EU)")).isTrue();
        assertThat(TapoPowerPollingService.isMeteringModel("P100")).isFalse();
        assertThat(TapoPowerPollingService.isMeteringModel("L530")).isFalse();
        assertThat(TapoPowerPollingService.isMeteringModel(null)).isFalse();
    }

    @Test
    void poll_allQueriesFail_reportsFailure() throws Exception {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO)).thenReturn(List.of(
                device("a", "Router", "P110", true, "192.168.1.61"),
                device("b", "Laufband", "P115", true, "192.168.1.62")));
        when(tapoDeviceService.getCurrentPower(any(), any(), isNull())).thenThrow(new RuntimeException("timeout"));

        service.poll();

        verify(jobMonitor).reportFailure(any());
    }

    @Test
    void poll_oneDeviceFails_doesNotReportFailure() throws Exception {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO)).thenReturn(List.of(
                device("a", "Router", "P110", true, "192.168.1.61"),
                device("b", "Laufband", "P115", true, "192.168.1.62")));
        when(tapoDeviceService.getCurrentPower(eq("a"), any(), isNull())).thenThrow(new RuntimeException("timeout"));
        when(tapoDeviceService.getCurrentPower(eq("b"), any(), isNull()))
                .thenReturn(objectMapper.readTree("{\"current_power\": 3}"));

        service.poll();

        verify(jobMonitor, never()).reportFailure(any());
    }

    @Test
    void poll_deviceListUnreadable_reportsFailure() {
        when(smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO))
                .thenThrow(new RuntimeException("DB weg"));

        service.poll();

        verify(jobMonitor).reportFailure(any());
    }

    @Test
    void poll_disabled_reportsInactive() {
        new TapoPowerPollingService(smartDeviceRepository, tapoDeviceService, entityStateService, jobMonitor, false).poll();

        verify(jobMonitor).reportInactive(any());
    }
}
