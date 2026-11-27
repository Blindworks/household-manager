package com.household.manager.tesla;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tesla.dto.TeslaStatusResponse;
import com.household.manager.tesla.dto.TeslaVehicleView;
import com.household.manager.tractive.GeoZone;
import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Einzige Stelle mit dem Fahrzeugstand: holt ihn bei Tessie, hält den letzten Erfolg im
 * Speicher (nach einem Neustart leer bis zum ersten Abruf), meldet die Entitäten und führt
 * Befehle aus. Ein gescheiterter Abruf lässt den letzten Stand stehen, die Entitäten werden
 * {@code unavailable} mit erhaltenen Attributen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeslaVehicleService {

    /**
     * „Zuhause“ = höchstens so weit vom Hundetracker-Zuhause (Admin → Hundetracker-Zuhause)
     * entfernt; mindestens 150 m, weil das Auto nicht im Hausmittelpunkt parkt.
     */
    static final double MIN_HOME_RADIUS_METERS = 150;

    private final TeslaProperties properties;
    private final TeslaSettingsService settingsService;
    private final TessieApiClient apiClient;
    private final TeslaEntityMapper entityMapper;
    private final EntityStateService entityStateService;
    private final TractiveHomeSettingsService homeSettingsService;
    private final AuditService auditService;
    private final Clock clock;

    private TeslaVehicleSnapshot vehicle;
    private Boolean atHome;
    private Instant polledAt;
    private Instant lastAttemptAt;
    private String lastError;
    /** VIN, die ohne Auswahl im Admin automatisch genommen wurde (erstes Fahrzeug des Kontos). */
    private String resolvedVin;
    private List<EntityStateUpdate> lastUpdates = List.of();

    public boolean isConfigured() {
        return properties.isEnabled() && settingsService.current().hasToken();
    }

    /** True, wenn seit dem letzten Versuch das eingestellte Intervall vergangen ist. */
    public synchronized boolean isDue() {
        if (lastAttemptAt == null) {
            return true;
        }
        Duration interval = Duration.ofMinutes(settingsService.current().pollIntervalMinutes());
        // 5 s Spielraum, damit ein 60-s-Takt ein 1-Minuten-Intervall nicht jedes zweite Mal verpasst
        return !clock.instant().isBefore(lastAttemptAt.plus(interval).minusSeconds(5));
    }

    /**
     * Holt den Stand bei Tessie und meldet die Entitäten.
     *
     * @throws TeslaException        wenn Tessie nicht antwortet
     * @throws IllegalStateException wenn nichts konfiguriert ist
     */
    public synchronized void refresh() {
        TeslaSettings settings = settingsService.current();
        if (!properties.isEnabled() || !settings.hasToken()) {
            throw new IllegalStateException("Tesla ist nicht eingerichtet (Admin → Tesla).");
        }
        lastAttemptAt = clock.instant();
        try {
            String vin = vinFor(settings);
            TeslaVehicleSnapshot snapshot = apiClient.getState(settings.apiToken(), vin);
            Boolean home = atHome(snapshot);
            vehicle = snapshot;
            atHome = home;
            polledAt = clock.instant();
            lastError = null;
            reportEntities(snapshot, home);
        } catch (RuntimeException ex) {
            lastError = ex.getMessage();
            markUnavailable();
            throw ex;
        }
    }

    public synchronized TeslaStatusResponse status() {
        TeslaSettings settings = settingsService.current();
        boolean configured = properties.isEnabled() && settings.hasToken();
        if (!configured) {
            return new TeslaStatusResponse(false, null, null, null);
        }
        boolean sameVehicle = vehicle != null
                && (settings.vin() == null || settings.vin().equalsIgnoreCase(vehicle.vin()));
        return new TeslaStatusResponse(true,
                sameVehicle ? toView(vehicle, atHome) : null,
                sameVehicle ? local(polledAt) : null,
                lastError);
    }

    /**
     * Führt einen Befehl aus und lädt danach den Stand neu. Scheitert nur das Nachladen,
     * gilt der Befehl trotzdem als ausgeführt.
     */
    public TeslaStatusResponse execute(TeslaCommand command) {
        TeslaSettings settings = settingsService.current();
        if (!properties.isEnabled() || !settings.hasToken()) {
            throw new IllegalStateException("Tesla ist nicht eingerichtet (Admin → Tesla).");
        }
        String vin;
        synchronized (this) {
            vin = vinFor(settings);
        }
        log.info("Tesla-Befehl {} für {}", command, vin);
        apiClient.sendCommand(settings.apiToken(), vin, command);
        auditService.record("tesla.command", command.name().toLowerCase(java.util.Locale.ROOT) + " " + vin);
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.warn("Tesla-Stand nach Befehl {} nicht nachgeladen: {}", command, ex.getMessage());
        }
        return status();
    }

    /** Fahrzeugliste des Kontos für die Admin-Seite (mit dem gespeicherten oder einem neuen Token). */
    public List<TessieVehicle> listVehicles(String token) {
        String effective = token != null && !token.isBlank() ? token.trim() : settingsService.current().apiToken();
        if (effective == null || effective.isBlank()) {
            throw new IllegalStateException("Bitte zuerst einen Tessie-Token eintragen.");
        }
        return apiClient.listVehicles(effective);
    }

    private String vinFor(TeslaSettings settings) {
        if (settings.vin() != null) {
            return settings.vin();
        }
        if (resolvedVin == null) {
            List<TessieVehicle> vehicles = apiClient.listVehicles(settings.apiToken());
            if (vehicles.isEmpty()) {
                throw new TeslaException("Im Tessie-Konto ist kein Fahrzeug.");
            }
            resolvedVin = vehicles.get(0).vin();
        }
        return resolvedVin;
    }

    private Boolean atHome(TeslaVehicleSnapshot snapshot) {
        if (snapshot.latitude() == null || snapshot.longitude() == null) {
            return null;
        }
        TractiveHomeSettings home;
        try {
            home = homeSettingsService.getSettings();
        } catch (Exception ex) {
            log.warn("Zuhause für Tesla nicht lesbar: {}", ex.getMessage());
            return null;
        }
        if (home.homeLatitude() == null || home.homeLongitude() == null) {
            return null;
        }
        double distance = GeoZone.distanceMeters(snapshot.latitude(), snapshot.longitude(),
                home.homeLatitude(), home.homeLongitude());
        return distance <= Math.max(MIN_HOME_RADIUS_METERS, home.homeRadiusMeters());
    }

    private void reportEntities(TeslaVehicleSnapshot snapshot, Boolean home) {
        try {
            List<EntityStateUpdate> updates = entityMapper.map(snapshot, home);
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
        } catch (Exception ex) {
            log.warn("Tesla-Entitäten nicht gemeldet: {}", ex.getMessage());
        }
    }

    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
            if ("unavailable".equals(update.state())) {
                continue;
            }
            try {
                entityStateService.reportState(EntityStateUpdate.builder()
                        .entityId(update.entityId())
                        .domain(update.domain())
                        .source(update.source())
                        .sourceRef(update.sourceRef())
                        .friendlyName(update.friendlyName())
                        .state("unavailable")
                        .attributes(update.attributes())
                        .build());
            } catch (Exception ex) {
                log.warn("Tesla-Entität {} nicht als unavailable gemeldet: {}", update.entityId(), ex.getMessage());
            }
        }
        lastUpdates = lastUpdates.stream()
                .map(u -> EntityStateUpdate.builder().entityId(u.entityId()).domain(u.domain())
                        .source(u.source()).sourceRef(u.sourceRef()).friendlyName(u.friendlyName())
                        .state("unavailable").attributes(u.attributes()).build())
                .toList();
    }

    private TeslaVehicleView toView(TeslaVehicleSnapshot v, Boolean home) {
        return new TeslaVehicleView(v.vin(), v.displayName(), v.vehicleState(), v.batteryLevel(), v.rangeKm(),
                v.chargeLimit(), v.chargingState(), v.chargingState() == null ? null : v.isCharging(),
                v.pluggedIn(), v.chargerPowerKw(), v.minutesToFullCharge(), v.scheduledChargingPending(),
                local(v.scheduledChargeAt()), v.chargePortOpen(), v.locked(), v.climateOn(), v.defrostOn(),
                v.insideTemp(), v.outsideTemp(), home, v.shiftState(), v.carType(), v.exteriorColor(),
                local(v.measuredAt()));
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }

    /** Wird beim Speichern der Einstellungen gerufen: neuer Token oder anderes Fahrzeug. */
    public synchronized void onSettingsChanged() {
        resolvedVin = null;
        lastAttemptAt = null;
        if (!Objects.equals(settingsService.current().vin(), vehicle == null ? null : vehicle.vin())) {
            lastError = null;
        }
    }
}
