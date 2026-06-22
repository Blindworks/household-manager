package com.household.manager.zigbee.service;

import com.household.manager.zigbee.dto.ZigbeeLiveResponse;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import com.household.manager.zigbee.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.repository.ZigbeeMeasurementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Aktualisiert das Geräte-Register, persistiert Messwerte und broadcastet sie live.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZigbeeReadingService {

    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final ZigbeeLiveService liveService;

    @Transactional
    public void record(ParsedZigbeeMessage message) {
        LocalDateTime now = LocalDateTime.now();
        ZigbeeDevice device = upsertDevice(message, now);

        for (ZigbeeMeasurementValue value : message.measurements()) {
            ZigbeeMeasurement measurement = ZigbeeMeasurement.builder()
                    .device(device)
                    .measurementType(value.type())
                    .value(value.value())
                    .unit(value.unit())
                    .measuredAt(now)
                    .build();
            measurementRepository.save(measurement);

            liveService.broadcast(ZigbeeLiveResponse.builder()
                    .friendlyName(device.getFriendlyName())
                    .measurementType(value.type())
                    .value(value.value())
                    .unit(value.unit())
                    .batteryPercent(device.getLastBatteryPercent())
                    .linkQuality(device.getLastLinkQuality())
                    .measuredAt(now)
                    .build());
        }
    }

    private ZigbeeDevice upsertDevice(ParsedZigbeeMessage message, LocalDateTime now) {
        ZigbeeDevice device = deviceRepository.findByFriendlyName(message.friendlyName())
                .orElseGet(() -> ZigbeeDevice.builder().friendlyName(message.friendlyName()).build());

        if (message.batteryPercent() != null) {
            device.setLastBatteryPercent(message.batteryPercent());
        }
        if (message.linkQuality() != null) {
            device.setLastLinkQuality(message.linkQuality());
        }
        device.setLastSeen(now);
        return deviceRepository.save(device);
    }
}
