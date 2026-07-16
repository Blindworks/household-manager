package com.household.manager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Laedt den ICS-Text von der konfigurierten Kalender-URL. Sonst nichts. */
@Component
@Slf4j
public class WasteCalendarIcsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param icsUrl vollstaendige http(s)-URL des Kalenders
     * @return roher ICS-Text
     * @throws WasteCalendarException bei Netzfehler, Timeout oder Status != 2xx
     */
    public String fetch(String icsUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(icsUrl))
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WasteCalendarException("Kalender-Abruf wurde unterbrochen.", ex);
        } catch (Exception ex) {
            throw new WasteCalendarException("Kalender ist nicht erreichbar: " + ex.getMessage(), ex);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WasteCalendarException(
                    "Kalender antwortete mit HTTP " + response.statusCode() + ".");
        }
        log.debug("ICS geladen: {} Zeichen", response.body().length());
        return response.body();
    }
}
