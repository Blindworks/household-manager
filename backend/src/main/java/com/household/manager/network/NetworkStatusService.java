package com.household.manager.network;

import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkDeviceRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Baut den aktuellen Netzwerk-Status aus dem juengsten Connectivity-Sample, dem juengsten
 * erfolgreichen Speedtest und dem In-Memory-Erreichbarkeitsstatus der aktiven LAN-Geraete
 * zusammen.
 */
@Service
@RequiredArgsConstructor
public class NetworkStatusService {

    private final NetworkConnectivitySampleRepository connectivityRepository;
    private final NetworkSpeedtestResultRepository speedtestRepository;
    private final NetworkDeviceRepository deviceRepository;
    private final NetworkDeviceStatusMonitor monitor;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NetworkDtos.StatusResponse getStatus() {
        return connectivityRepository.findTopByOrderBySampledAtDesc()
                .map(this::fromSample)
                .orElseGet(this::noSampleYet);
    }

    /**
     * Ohne jeden Connectivity-Sample (Erststart, Ausfall vor dem ersten Poll) wird
     * bewusst {@code online=false} mit {@code lastCheckedAt=null} gemeldet statt
     * irgendeinen Wert zu raten - das Frontend zeigt dafuer "noch keine Messung".
     */
    private NetworkDtos.StatusResponse noSampleYet() {
        return new NetworkDtos.StatusResponse(false, null, false, null, lastSpeedtest(), devices());
    }

    private NetworkDtos.StatusResponse fromSample(NetworkConnectivitySample sample) {
        return new NetworkDtos.StatusResponse(
                sample.isOnline(), sample.getLatencyMs(), sample.isGatewayReachable(),
                sample.getSampledAt(), lastSpeedtest(), devices());
    }

    private NetworkDtos.SpeedtestSummary lastSpeedtest() {
        return speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()
                .map(NetworkDtos.SpeedtestSummary::from)
                .orElse(null);
    }

    private List<NetworkDtos.DeviceStatusResponse> devices() {
        return deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(this::toDeviceStatus)
                .toList();
    }

    private NetworkDtos.DeviceStatusResponse toDeviceStatus(NetworkDevice device) {
        return monitor.statusOf(device.getId())
                .map(status -> new NetworkDtos.DeviceStatusResponse(
                        device.getId(), device.getName(), device.getHost(),
                        status.reachable(), toLocalDateTime(status.lastSeenAt())))
                .orElseGet(() -> new NetworkDtos.DeviceStatusResponse(
                        device.getId(), device.getName(), device.getHost(), false, null));
    }

    /**
     * {@code NetworkDeviceStatusMonitor} haelt {@code lastSeenAt} als {@code Instant} (reiner
     * Speicher, Muster {@code ZigbeeStreamMonitor}); die API antwortet aber durchgehend mit
     * lokaler Haushaltszeit wie {@code lastCheckedAt} und {@code testedAt} - sonst muesste das
     * Frontend zwei Zeitformate im selben Antwortbaum parsen. Die injizierte {@link Clock} traegt
     * die massgebliche Zone, nicht hart Europe/Berlin.
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
