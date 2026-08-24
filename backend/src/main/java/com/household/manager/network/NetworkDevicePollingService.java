package com.household.manager.network;

import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.repository.NetworkDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Prueft periodisch die TCP-Erreichbarkeit aller aktiven LAN-Geraete und pflegt den
 * Ergebnis-Status in {@link NetworkDeviceStatusMonitor} (reiner Speicher, siehe dort).
 * Geraete bekommen bewusst keine eigenen Entitaeten - v1 ist ein Dashboard-Feature ohne
 * Flow-Anbindung.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NetworkDevicePollingService {

    /** Wird nur genutzt, wenn das Geraet keinen eigenen Port hinterlegt hat. */
    private static final List<Integer> FALLBACK_PORTS = List.of(80, 443, 22, 1883, 8080, 8443);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final NetworkDeviceRepository repository;
    private final TcpPortProbe tcpPortProbe;
    private final NetworkDeviceStatusMonitor monitor;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${network.devices.poll-interval-ms:60000}")
    public void poll() {
        List<NetworkDevice> devices;
        try {
            devices = repository.findByActiveTrueOrderBySortOrderAscIdAsc();
        } catch (Exception e) {
            log.warn("Laden der LAN-Geraete fuer die Erreichbarkeitspruefung fehlgeschlagen", e);
            return;
        }

        Instant now = clock.instant();
        for (NetworkDevice device : devices) {
            boolean reachable = isReachable(device);
            monitor.update(device.getId(), reachable, now);
        }
    }

    private boolean isReachable(NetworkDevice device) {
        try {
            if (device.getTcpPort() != null) {
                return tcpPortProbe.isOpen(device.getHost(), device.getTcpPort(), PROBE_TIMEOUT);
            }
            for (int port : FALLBACK_PORTS) {
                if (tcpPortProbe.isOpen(device.getHost(), port, PROBE_TIMEOUT)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Erreichbarkeitspruefung fuer Geraet {} ({}) fehlgeschlagen: {}",
                    device.getId(), device.getHost(), e.getMessage());
            return false;
        }
    }
}
