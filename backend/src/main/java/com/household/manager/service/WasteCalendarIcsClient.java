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
    /** Ein ICS-Objekt beginnt laut RFC 5545 immer mit dieser Zeile. */
    private static final String ICS_PREFIX = "BEGIN:VCALENDAR";
    /** Byte Order Mark; manche Server stellen sie einer UTF-8-Datei voran. */
    private static final char BOM = 0xFEFF;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param icsUrl vollstaendige http(s)-URL des Kalenders
     * @return roher ICS-Text
     * @throws WasteCalendarException bei Netzfehler, Timeout, Status != 2xx oder wenn die
     *         Antwort kein Kalender ist
     */
    public String fetch(String icsUrl) {
        HttpResponse<String> response;
        try {
            // URL-Parsing bewusst im try: URI.create wirft bei einer vertippten URL eine
            // ungepruefte IllegalArgumentException (bzw. NPE bei null). Ausserhalb des try
            // wuerde sie an WasteCalendarException vorbeilaufen und den Kontrakt brechen.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(icsUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new WasteCalendarException("Ungueltige Kalender-URL: " + icsUrl, ex);
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
        requireCalendar(response);
        log.debug("ICS geladen: {} Zeichen", response.body().length());
        return response.body();
    }

    /**
     * Weist alles zurueck, was kein Kalender ist — sonst reicht der Client eine HTML-Seite
     * an den Parser weiter, der darin keine Termine findet. Das sieht dann wie ein leerer
     * Kalender aus statt wie eine falsch hinterlegte URL (die Einbetten-Adresse eines
     * Google-Kalenders etwa liefert HTML mit Status 200).
     */
    private void requireCalendar(HttpResponse<String> response) {
        if (looksLikeCalendar(response.body())) {
            return;
        }
        String contentType = response.headers().firstValue("content-type").orElse("unbekannt");
        throw new WasteCalendarException(
                "Die URL liefert keinen Kalender, sondern " + contentType
                        + ". Erwartet wird eine ICS-Datei — bei Google die Adresse im"
                        + " iCal-Format, nicht der Link zur Kalenderansicht.");
    }

    /**
     * Prueft den Inhalt und nicht den Content-Type: Manche Server liefern ICS als
     * text/plain oder application/octet-stream aus, waehrend eine Fehlerseite durchaus
     * text/calendar behaupten kann. Die Anfangszeile ist das verlaessliche Merkmal.
     */
    private boolean looksLikeCalendar(String body) {
        String start = body.isEmpty() || body.charAt(0) != BOM ? body : body.substring(1);
        return start.stripLeading().regionMatches(true, 0, ICS_PREFIX, 0, ICS_PREFIX.length());
    }
}
