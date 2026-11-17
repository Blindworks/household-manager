package com.household.manager.rainradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Holt die Radarbilder fuer einen Ort von Bright Sky ({@code api.brightsky.dev/radar}),
 * dem freien Aufbereiter des DWD-Radarkomposits RV (1 km², 5 Minuten, Nowcast 2 h).
 *
 * <p>Angefragt wird ein 3×3-Pixel-Ausschnitt ({@code distance=1000}) von 20 Minuten vor
 * bis gut zwei Stunden nach jetzt. Bright Sky verlangt fuer {@code format=plain} eine
 * Kompression; der Java-{@code HttpClient} entpackt nicht selbst, deshalb hier gzip von Hand.
 * HTTP/1.1 erzwungen (Projektregel gegen Python-Server).
 */
@Component
public class RainRadarClient {

    private static final Duration LOOK_BACK = Duration.ofMinutes(20);
    private static final Duration LOOK_AHEAD = Duration.ofMinutes(130);

    private final RainRadarProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public RainRadarClient(RainRadarProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public List<RainRadarFrame> fetch(double latitude, double longitude, Instant now)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(buildUri(latitude, longitude, now))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = decode(response)) {
            if (response.statusCode() != 200) {
                throw new IOException("Bright Sky antwortet mit HTTP " + response.statusCode());
            }
            return RainRadarParser.parse(mapper.readTree(body));
        }
    }

    URI buildUri(double latitude, double longitude, Instant now) {
        Instant from = now.minus(LOOK_BACK).truncatedTo(ChronoUnit.MINUTES);
        Instant to = now.plus(LOOK_AHEAD).truncatedTo(ChronoUnit.MINUTES);
        return URI.create(String.format(Locale.ROOT,
                "%s?lat=%.5f&lon=%.5f&distance=1000&format=plain&date=%s&last_date=%s",
                properties.getBaseUrl(), latitude, longitude, from, to));
    }

    private static InputStream decode(HttpResponse<InputStream> response) throws IOException {
        boolean gzip = response.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase(Locale.ROOT).contains("gzip"))
                .orElse(false);
        return gzip ? new GZIPInputStream(response.body()) : response.body();
    }
}
