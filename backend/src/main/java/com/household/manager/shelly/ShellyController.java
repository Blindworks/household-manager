package com.household.manager.shelly;

import com.household.manager.model.entity.ShellyReading;
import com.household.manager.repository.ShellyReadingRepository;
import com.household.manager.shelly.dto.ShellyReadingResponse;
import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/shelly")
@RequiredArgsConstructor
public class ShellyController {

    private final ShellyService shellyService;
    private final ShellyLiveService shellyLiveService;
    private final ShellyReadingRepository shellyReadingRepository;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return shellyLiveService.subscribe();
    }

    @GetMapping
    public ResponseEntity<List<ShellyStatusDto>> getAllDevicesStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shellyService.getAllDevicesStatus());
    }

    @GetMapping("/{deviceName}/readings")
    public ResponseEntity<List<ShellyReadingResponse>> getReadings(@PathVariable String deviceName) {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<ShellyReadingResponse> readings = shellyReadingRepository
                .findByDeviceNameAndTimestampBetweenOrderByTimestampAsc(deviceName, since, LocalDateTime.now())
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(readings);
    }

    @PostMapping("/{deviceName}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String deviceName) {
        shellyService.turnOn(deviceName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{deviceName}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String deviceName) {
        shellyService.turnOff(deviceName);
        return ResponseEntity.ok().build();
    }

    private ShellyReadingResponse toResponse(ShellyReading reading) {
        return new ShellyReadingResponse(
                reading.getId(),
                reading.getDeviceName(),
                reading.getTimestamp(),
                reading.getPower(),
                reading.getVoltage(),
                reading.getCurrentA(),
                reading.getTotalEnergy()
        );
    }
}
