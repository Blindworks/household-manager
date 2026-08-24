package com.household.manager.network;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Misst minuetlich Internet-Erreichbarkeit und -Latenz gegen zwei feste HTTP-Ziele sowie
 * die Erreichbarkeit des lokalen Gateways, speichert das Sample und spiegelt es als
 * {@code binary_sensor.network_internet} / {@code sensor.network_latency_ms} in den
 * Entity-State-Layer (Flow-Trigger-faehig).
 * <p>
 * Kein ICMP: {@code InetAddress.isReachable} faellt im Docker-Bridge-Container still auf
 * TCP-Port 7 zurueck, deshalb ausschliesslich HTTP-/TCP-Checks.
 */
@Service
@Slf4j
public class NetworkConnectivityPollingService {

    private static final URI CLOUDFLARE = URI.create("https://1.1.1.1/cdn-cgi/trace");
    private static final URI GSTATIC = URI.create("https://www.gstatic.com/generate_204");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration GATEWAY_TIMEOUT = Duration.ofSeconds(2);

    private static final String INTERNET_ENTITY_ID = "binary_sensor.network_internet";
    private static final String LATENCY_ENTITY_ID = "sensor.network_latency_ms";

    private final ConnectivityProbe connectivityProbe;
    private final TcpPortProbe tcpPortProbe;
    private final NetworkConnectivitySampleRepository repository;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final String gatewayIp;

    /**
     * Einziger Konstruktor mit allen Abhaengigkeiten inklusive dem {@code @Value}-Parameter:
     * Spring waehlt bei genau einem Konstruktor automatisch diesen, ein zweiter (z. B. fuer
     * Tests) wuerde den Anwendungsstart brechen (bekannte Projekt-Falle, Commit 926812b).
     */
    public NetworkConnectivityPollingService(
            ConnectivityProbe connectivityProbe,
            TcpPortProbe tcpPortProbe,
            NetworkConnectivitySampleRepository repository,
            EntityStateService entityStateService,
            Clock clock,
            @Value("${network.gateway-ip:192.168.1.1}") String gatewayIp) {
        this.connectivityProbe = connectivityProbe;
        this.tcpPortProbe = tcpPortProbe;
        this.repository = repository;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.gatewayIp = gatewayIp;
    }

    @Scheduled(fixedDelayString = "${network.connectivity.poll-interval-ms:60000}")
    public void poll() {
        try {
            Optional<Duration> cloudflare = safeProbe(CLOUDFLARE);
            Optional<Duration> gstatic = safeProbe(GSTATIC);

            boolean online = cloudflare.isPresent() || gstatic.isPresent();
            Integer latencyMs = online ? minLatencyMs(cloudflare, gstatic) : null;
            boolean gatewayReachable = isGatewayReachable();

            NetworkConnectivitySample sample = NetworkConnectivitySample.builder()
                    .sampledAt(LocalDateTime.now(clock))
                    .online(online)
                    .latencyMs(latencyMs)
                    .gatewayReachable(gatewayReachable)
                    .build();
            repository.save(sample);

            reportEntities(online, latencyMs, gatewayReachable);
        } catch (Exception e) {
            log.warn("Netzwerk-Konnektivitaetspruefung fehlgeschlagen", e);
        }
    }

    /**
     * {@link ConnectivityProbe#probe} soll laut Vertrag nie werfen, aber ein einzelnes
     * fehlerhaftes Ziel darf die Auswertung des jeweils anderen nicht verhindern -
     * deshalb hier zusaetzlich defensiv statt sich allein auf den Vertrag zu verlassen.
     */
    private Optional<Duration> safeProbe(URI target) {
        try {
            return connectivityProbe.probe(target, PROBE_TIMEOUT);
        } catch (Exception e) {
            log.debug("Konnektivitaetspruefung gegen {} fehlgeschlagen: {}", target, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isGatewayReachable() {
        return tcpPortProbe.isOpen(gatewayIp, 80, GATEWAY_TIMEOUT)
                || tcpPortProbe.isOpen(gatewayIp, 443, GATEWAY_TIMEOUT);
    }

    /** Nur aufzurufen, wenn mindestens eines der beiden Ergebnisse vorhanden ist. */
    private static Integer minLatencyMs(Optional<Duration> a, Optional<Duration> b) {
        Long aMs = a.map(Duration::toMillis).orElse(null);
        Long bMs = b.map(Duration::toMillis).orElse(null);
        if (aMs == null) {
            return bMs.intValue();
        }
        if (bMs == null) {
            return aMs.intValue();
        }
        return (int) Math.min(aMs, bMs);
    }

    private void reportEntities(boolean online, Integer latencyMs, boolean gatewayReachable) {
        Map<String, Object> internetAttributes = new HashMap<>();
        internetAttributes.put("deviceClass", "connectivity");
        internetAttributes.put("gatewayReachable", gatewayReachable);
        if (online) {
            internetAttributes.put("latencyMs", latencyMs);
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(INTERNET_ENTITY_ID)
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.NETWORK)
                .sourceRef("internet")
                .friendlyName("Internetverbindung")
                .state(online ? "on" : "off")
                .attributes(internetAttributes)
                .build());

        if (online) {
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(LATENCY_ENTITY_ID)
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.NETWORK)
                    .sourceRef("internet")
                    .friendlyName("Internet-Latenz")
                    .state(String.valueOf(latencyMs))
                    .attributes(Map.of("unit", "ms"))
                    .build());
        }
    }
}
