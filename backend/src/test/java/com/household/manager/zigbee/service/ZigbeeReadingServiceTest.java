package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import com.household.manager.zigbee.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.repository.ZigbeeMeasurementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ZigbeeReadingServiceTest {

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private ZigbeeMeasurementRepository measurementRepository;
    @Mock private ZigbeeLiveService liveService;

    @InjectMocks private ZigbeeReadingService service;

    private ParsedZigbeeMessage climateMessage;

    @BeforeEach
    void setUp() {
        climateMessage = new ParsedZigbeeMessage(
                "Wohnzimmer-Klima", 90, 120,
                List.of(new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("21.5"), "°C")));
    }

    @Test
    void createsDeviceOnFirstMessage() {
        when(deviceRepository.findByFriendlyName("Wohnzimmer-Klima")).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        ArgumentCaptor<ZigbeeDevice> captor = ArgumentCaptor.forClass(ZigbeeDevice.class);
        verify(deviceRepository).save(captor.capture());
        ZigbeeDevice saved = captor.getValue();
        assertThat(saved.getFriendlyName()).isEqualTo("Wohnzimmer-Klima");
        assertThat(saved.getLastBatteryPercent()).isEqualTo(90);
        assertThat(saved.getLastLinkQuality()).isEqualTo(120);
        assertThat(saved.getLastSeen()).isNotNull();
    }

    @Test
    void updatesExistingDevice() {
        ZigbeeDevice existing = ZigbeeDevice.builder()
                .id(1L).friendlyName("Wohnzimmer-Klima").lastBatteryPercent(50).build();
        when(deviceRepository.findByFriendlyName("Wohnzimmer-Klima")).thenReturn(Optional.of(existing));
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        assertThat(existing.getLastBatteryPercent()).isEqualTo(90);
        verify(deviceRepository, never()).save(argThat(d -> d.getId() == null));
    }

    @Test
    void persistsMeasurementWithDeviceUnitAndType() {
        when(deviceRepository.findByFriendlyName(any())).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        ArgumentCaptor<ZigbeeMeasurement> captor = ArgumentCaptor.forClass(ZigbeeMeasurement.class);
        verify(measurementRepository).save(captor.capture());
        ZigbeeMeasurement m = captor.getValue();
        assertThat(m.getMeasurementType()).isEqualTo(MeasurementType.TEMPERATURE);
        assertThat(m.getValue()).isEqualByComparingTo("21.5");
        assertThat(m.getUnit()).isEqualTo("°C");
        assertThat(m.getMeasuredAt()).isNotNull();
    }

    @Test
    void broadcastsEachMeasurementLive() {
        when(deviceRepository.findByFriendlyName(any())).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        verify(liveService, times(1)).broadcast(any());
    }
}
