package com.household.manager.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP-Client fuer den Rebooter-Sidecar. Der Sidecar antwortet sofort mit 202
 * und startet die Container asynchron neu — die Antwort erreicht den Aufrufer
 * also noch, bevor das Backend selbst neu startet.
 */
@Service
@Slf4j
public class RebooterClient {

    private final RebooterProperties properties;
    private final HttpClient httpClient;

    public RebooterClient(RebooterProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Fordert den Neustart aller Compose-Container an. */
    public void triggerReboot() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/reboot"))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("X-Rebooter-Token", properties.getToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 202) {
                throw new RebooterException("Rebooter-Sidecar antwortete mit HTTP " + response.statusCode());
            }
        } catch (RebooterException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RebooterException("Rebooter-Aufruf unterbrochen", ex);
        } catch (Exception ex) {
            throw new RebooterException("Rebooter-Sidecar nicht erreichbar", ex);
        }
    }
}
