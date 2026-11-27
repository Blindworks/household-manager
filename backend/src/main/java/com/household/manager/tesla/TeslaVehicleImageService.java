package com.household.manager.tesla;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Fahrzeugbild der Kachel: eigene Adresse aus Admin → Tesla oder Teslas Konfiguratorbild
 * ({@link TeslaCompositorImage}). Das Backend lädt es selbst und hält es im Speicher, damit das
 * Wandtablet nur das LAN braucht und Tesla nicht bei jedem Seitenaufruf gefragt wird.
 */
@Service
@Slf4j
public class TeslaVehicleImageService {

    static final Duration CACHE_TTL = Duration.ofDays(7);
    /** Nach einem Fehlschlag nicht gleich wieder fragen (die Kachel lädt jede Minute). */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(15);
    static final int MAX_BYTES = 5 * 1024 * 1024;

    public record VehicleImage(byte[] bytes, String contentType) {
    }

    private final TeslaSettingsService settingsService;
    private final TeslaVehicleService vehicleService;
    private final HttpClient httpClient;
    private final Clock clock;

    private String cachedUrl;
    private VehicleImage cached;
    private Instant cachedAt;
    private String failedUrl;
    private Instant failedAt;

    @Autowired
    public TeslaVehicleImageService(TeslaSettingsService settingsService, TeslaVehicleService vehicleService,
                                    Clock clock) {
        this(settingsService, vehicleService, clock, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    TeslaVehicleImageService(TeslaSettingsService settingsService, TeslaVehicleService vehicleService,
                             Clock clock, HttpClient httpClient) {
        this.settingsService = settingsService;
        this.vehicleService = vehicleService;
        this.clock = clock;
        this.httpClient = httpClient;
    }

    /** Adresse des Bilds, das gerade gilt, oder {@code null}. */
    public String currentUrl() {
        String own = settingsService.current().imageUrl();
        return own != null ? own : TeslaCompositorImage.urlFor(vehicleService.currentVehicle());
    }

    /** Kurzer Schlüssel der Bildadresse, damit der Browser bei einer neuen Adresse neu lädt. */
    public String version() {
        String url = currentUrl();
        if (url == null) {
            return null;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /**
     * Das Bild oder leer, wenn es keine Adresse gibt.
     *
     * @throws TeslaException wenn die Adresse kein Bild liefert
     */
    public synchronized Optional<VehicleImage> image() {
        String url = currentUrl();
        if (url == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (url.equals(cachedUrl) && cached != null && now.isBefore(cachedAt.plus(CACHE_TTL))) {
            return Optional.of(cached);
        }
        if (url.equals(failedUrl) && now.isBefore(failedAt.plus(RETRY_AFTER_FAILURE))) {
            if (url.equals(cachedUrl) && cached != null) {
                return Optional.of(cached);
            }
            throw new TeslaException("Fahrzeugbild nicht ladbar (neuer Versuch in Kürze).");
        }
        try {
            VehicleImage loaded = fetch(url);
            cachedUrl = url;
            cached = loaded;
            cachedAt = now;
            failedUrl = null;
            return Optional.of(loaded);
        } catch (TeslaException ex) {
            failedUrl = url;
            failedAt = now;
            log.warn("Fahrzeugbild nicht geladen: {}", ex.getMessage());
            if (url.equals(cachedUrl) && cached != null) {
                return Optional.of(cached);
            }
            throw ex;
        }
    }

    private VehicleImage fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "image/*")
                .header("User-Agent", "Mozilla/5.0 (household-manager)")
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new TeslaException("Bildadresse nicht erreichbar: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TeslaException("Bildabruf unterbrochen", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new TeslaException("Bildadresse antwortet mit HTTP " + response.statusCode() + ".");
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("")
                .split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw new TeslaException("Die Bildadresse liefert kein Bild (" + contentType + ").");
        }
        byte[] body = response.body();
        if (body == null || body.length == 0 || body.length > MAX_BYTES) {
            throw new TeslaException("Das Fahrzeugbild ist leer oder größer als 5 MB.");
        }
        return new VehicleImage(body, contentType);
    }
}
