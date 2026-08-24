package com.household.manager.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Prueft HTTP-Erreichbarkeit per GET. Jeder Statuscode (2xx-4xx) zaehlt als erreichbar -
 * es geht ausschliesslich um Konnektivitaet, nicht um die Anwendungsantwort.
 * <p>
 * HTTP_1_1 ist erzwungen: der Java-HttpClient-Default HTTP_2 verschluckt Requests gegen
 * manche Gegenstellen still (bekannte Projekt-Falle, siehe java-httpclient-uvicorn-body).
 */
@Component
@Slf4j
public class HttpConnectivityProbe implements ConnectivityProbe {

    @Override
    public Optional<Duration> probe(URI target, Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .GET()
                .build();
        try {
            Instant start = Instant.now();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return Optional.of(Duration.between(start, Instant.now()));
        } catch (Exception e) {
            log.debug("Konnektivitaetspruefung gegen {} fehlgeschlagen: {}", target, e.getMessage());
            return Optional.empty();
        }
    }
}
