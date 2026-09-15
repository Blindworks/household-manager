package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.ZigbeeBridgeUnavailableException;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Request/Response gegen die zigbee2mqtt-Bridge-API: Request auf
 * {@code zigbee2mqtt/bridge/request/<request>} mit Transaktions-ID, Antwort kommt
 * asynchron auf {@code bridge/response/<request>} und traegt dieselbe ID.
 * <p>
 * Timeout ist Pflicht — ohne es hinge ein HTTP-Thread ewig, wenn z2m gerade weg ist.
 * Kein Request ohne bestehende Verbindung: HiveMQ wuerde das Publish sonst puffern und
 * Minuten spaeter nachholen, ein verspaetet geoeffnetes Anlernfenster waere eine
 * Ueberraschung.
 */
@Service
@Slf4j
public class ZigbeeBridgeRequestService {

    private static final String REQUEST_PREFIX = "zigbee2mqtt/bridge/request/";

    private final ZigbeeBridgeCommands commands;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final Map<String, CompletableFuture<ZigbeeBridgeResponse>> pending = new ConcurrentHashMap<>();

    @Autowired
    public ZigbeeBridgeRequestService(ZigbeeBridgeCommands commands, ObjectMapper objectMapper,
                                      @Value("${zigbee.bridge.request-timeout-seconds:10}") int timeoutSeconds) {
        this(commands, objectMapper, Duration.ofSeconds(timeoutSeconds));
    }

    ZigbeeBridgeRequestService(ZigbeeBridgeCommands commands, ObjectMapper objectMapper, Duration timeout) {
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    /**
     * @param request Pfad nach {@code bridge/request/}, z. B. {@code permit_join} oder {@code device/rename}
     * @param params  Felder des Requests; {@code transaction} wird ergaenzt
     * @throws ZigbeeBridgeUnavailableException nicht verbunden, Publish gescheitert oder Timeout
     * @throws ZigbeeBridgeRejectedException    z2m antwortet mit status error
     */
    public ZigbeeBridgeResponse send(String request, Map<String, Object> params) {
        if (!commands.isConnected()) {
            throw new ZigbeeBridgeUnavailableException(
                    "Keine Verbindung zum MQTT-Broker — zigbee2mqtt nicht erreichbar.");
        }
        String transaction = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("transaction", transaction);
        CompletableFuture<ZigbeeBridgeResponse> future = new CompletableFuture<>();
        pending.put(transaction, future);
        try {
            String json = objectMapper.writeValueAsString(payload);
            commands.publish(REQUEST_PREFIX + request, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ZigbeeBridgeResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!response.ok()) {
                throw new ZigbeeBridgeRejectedException(
                        response.error() != null ? response.error() : "zigbee2mqtt hat den Request abgelehnt.");
            }
            return response;
        } catch (TimeoutException ex) {
            throw new ZigbeeBridgeUnavailableException("zigbee2mqtt antwortet nicht (" + request + ").");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ZigbeeBridgeUnavailableException("Warten auf zigbee2mqtt unterbrochen.");
        } catch (ZigbeeBridgeRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ZigbeeBridgeUnavailableException("Request an zigbee2mqtt fehlgeschlagen: " + ex.getMessage());
        } finally {
            pending.remove(transaction);
        }
    }

    /** Vom MQTT-Handler fuer jede {@code bridge/response/*}-Nachricht aufzurufen. */
    public void onResponse(ZigbeeBridgeResponse response) {
        if (response.transaction() == null) {
            return;
        }
        CompletableFuture<ZigbeeBridgeResponse> future = pending.remove(response.transaction());
        if (future != null) {
            future.complete(response);
        }
    }

    int pendingCount() {
        return pending.size();
    }
}
