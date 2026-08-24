package com.household.manager.network;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fuehrt stuendlich (und auf Anfrage manuell) einen Cloudflare-Speedtest aus, speichert das
 * Ergebnis und spiegelt Download-/Upload-Rate in den Entity-State-Layer.
 * <p>
 * Download und Upload werden unabhaengig gemessen: scheitert eine Richtung, bleibt sie
 * {@code null} in der gespeicherten Zeile, die andere zaehlt trotzdem als Erfolg
 * ({@code success = downloadMbps != null || uploadMbps != null}) - ein Ausreisser bei nur
 * einer Richtung soll die andere nicht mit verschlucken.
 */
@Service
@Slf4j
public class NetworkSpeedtestService {

    private static final String DOWNLOAD_ENTITY_ID = "sensor.network_download_mbps";
    private static final String UPLOAD_ENTITY_ID = "sensor.network_upload_mbps";
    private static final Duration MANUAL_COOLDOWN = Duration.ofSeconds(60);

    private final SpeedtestClient speedtestClient;
    private final NetworkSpeedtestResultRepository repository;
    private final NetworkConnectivitySampleRepository connectivityRepository;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final Duration budget;

    private final AtomicReference<Instant> lastManualRun = new AtomicReference<>();

    /**
     * Einziger Konstruktor mit allen Abhaengigkeiten inklusive dem {@code @Value}-Parameter
     * (Muster: {@code NetworkConnectivityPollingService}).
     */
    public NetworkSpeedtestService(
            SpeedtestClient speedtestClient,
            NetworkSpeedtestResultRepository repository,
            NetworkConnectivitySampleRepository connectivityRepository,
            EntityStateService entityStateService,
            Clock clock,
            @Value("${network.speedtest.budget-seconds:10}") int budgetSeconds) {
        this.speedtestClient = speedtestClient;
        this.repository = repository;
        this.connectivityRepository = connectivityRepository;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.budget = Duration.ofSeconds(budgetSeconds);
    }

    @Scheduled(
            fixedDelayString = "${network.speedtest.interval-ms:3600000}",
            initialDelayString = "${network.speedtest.initial-delay-ms:120000}")
    public void runScheduled() {
        try {
            if (isOffline()) {
                log.debug("Speedtest uebersprungen - letzter Connectivity-Sample meldet offline");
                return;
            }
            runMeasurement();
        } catch (Exception e) {
            log.warn("Geplanter Speedtest fehlgeschlagen", e);
        }
    }

    /**
     * Manueller Trigger (Controller-Pfad). Darf werfen - {@code TooManyRequestsException} bei
     * Cooldown-Verstoss, {@code IllegalStateException} ohne Internet. Liefert das gerade
     * gemessene und gespeicherte Ergebnis zurueck, damit der Controller es direkt beantworten
     * kann, ohne den Speedtest-Repository-Zugriff selbst zu duplizieren.
     * <p>
     * Der Cooldown-Slot wird VOR {@link #runMeasurement()} atomar reserviert (compareAndSet),
     * nicht erst danach: sonst koennten zwei fast gleichzeitige Aufrufe (Doppelklick, zwei Tabs)
     * beide denselben alten Stand lesen, beide den Check passieren und beide messen - genau das
     * soll der Cooldown verhindern. Schlaegt {@link #runMeasurement()} anschliessend fehl, bleibt
     * der Slot trotzdem reserviert (bewusst in Kauf genommen, statt den Cooldown zurueckzurollen -
     * verhindert, dass ein kaputtes Ziel im Sekundentakt erneut angefragt wird).
     */
    public NetworkSpeedtestResult runManual() {
        if (isOffline()) {
            throw new IllegalStateException("Kein Internet — Speedtest nicht möglich.");
        }
        reserveManualSlot();
        return runMeasurement();
    }

    private void reserveManualSlot() {
        Instant now = Instant.now(clock);
        Instant last = lastManualRun.get();
        if (last != null && Duration.between(last, now).compareTo(MANUAL_COOLDOWN) < 0) {
            throw new TooManyRequestsException(
                    "Speedtest wurde erst vor Kurzem ausgefuehrt - bitte kurz warten.");
        }
        if (!lastManualRun.compareAndSet(last, now)) {
            // Ein paralleler Aufruf hat den Slot zwischen unserem Lesen und Reservieren belegt.
            throw new TooManyRequestsException(
                    "Speedtest wurde erst vor Kurzem ausgefuehrt - bitte kurz warten.");
        }
    }

    /**
     * Ohne jeden Connectivity-Sample (Erststart) findet der Lauf statt statt zu blockieren.
     */
    private boolean isOffline() {
        return connectivityRepository.findTopByOrderBySampledAtDesc()
                .map(sample -> !sample.isOnline())
                .orElse(false);
    }

    private NetworkSpeedtestResult runMeasurement() {
        BigDecimal download = null;
        BigDecimal upload = null;
        String downloadError = null;
        String uploadError = null;

        try {
            download = speedtestClient.measureDownloadMbps(budget);
        } catch (Exception e) {
            downloadError = "Download: " + e.getMessage();
            log.warn("Speedtest-Download fehlgeschlagen: {}", e.getMessage());
        }
        try {
            upload = speedtestClient.measureUploadMbps(budget);
        } catch (Exception e) {
            uploadError = "Upload: " + e.getMessage();
            log.warn("Speedtest-Upload fehlgeschlagen: {}", e.getMessage());
        }

        boolean success = download != null || upload != null;
        String errorMessage = combineErrors(downloadError, uploadError);

        NetworkSpeedtestResult result = NetworkSpeedtestResult.builder()
                .testedAt(LocalDateTime.now(clock))
                .downloadMbps(download)
                .uploadMbps(upload)
                .success(success)
                .errorMessage(errorMessage)
                .build();
        repository.save(result);

        if (download != null) {
            mirrorEntity(DOWNLOAD_ENTITY_ID, "Download-Geschwindigkeit", download);
        }
        if (upload != null) {
            mirrorEntity(UPLOAD_ENTITY_ID, "Upload-Geschwindigkeit", upload);
        }

        return result;
    }

    private static String combineErrors(String downloadError, String uploadError) {
        if (downloadError == null) {
            return uploadError;
        }
        if (uploadError == null) {
            return downloadError;
        }
        return downloadError + "; " + uploadError;
    }

    private void mirrorEntity(String entityId, String friendlyName, BigDecimal mbps) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.NETWORK)
                .sourceRef("speedtest")
                .friendlyName(friendlyName)
                .state(mbps.stripTrailingZeros().toPlainString())
                .attributes(Map.of("unit", "Mbit/s"))
                .build());
    }
}
