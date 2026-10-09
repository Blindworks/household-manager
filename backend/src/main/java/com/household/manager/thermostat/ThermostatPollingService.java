package com.household.manager.thermostat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pollt die Hubs und spiegelt die Thermostate in Cache, Verlauf und Entity-State-Layer.
 * Ein Hub-Aufruf je Hub und Zyklus, nie einer je Kindgeraet. Ein Fehler an einem Hub betrifft
 * nur dessen Thermostate; die geplante Methode wirft nie.
 */
@Service
@ConditionalOnProperty(name = "thermostat.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ThermostatPollingService {

    private static final TypeReference<Map<String, Object>> ATTRIBUTE_MAP = new TypeReference<>() { };

    private final ThermostatRepository repository;
    private final TapoHubClient hubClient;
    private final ThermostatStateCache cache;
    private final ThermostatEntityMapper entityMapper;
    private final ThermostatHistoryRecorder historyRecorder;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${thermostat.poll-interval-ms:60000}",
            initialDelayString = "${thermostat.initial-delay-ms:20000}")
    public void poll() {
        try {
            groupByHub(repository.findByActiveTrue()).forEach(this::pollHubIsolated);
        } catch (Exception ex) {
            log.warn("Thermostat-Polling fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private Map<String, List<Thermostat>> groupByHub(List<Thermostat> thermostats) {
        Map<String, List<Thermostat>> byHub = new LinkedHashMap<>();
        for (Thermostat thermostat : thermostats) {
            if (thermostat.getHubIp() != null && !thermostat.getHubIp().isBlank()) {
                byHub.computeIfAbsent(thermostat.getHubIp(), ip -> new ArrayList<>()).add(thermostat);
            }
        }
        return byHub;
    }

    private void pollHubIsolated(String hubIp, List<Thermostat> thermostats) {
        try {
            pollHub(hubIp, thermostats);
        } catch (Exception ex) {
            log.warn("Polling des Hubs {} fehlgeschlagen: {}", hubIp, ex.getMessage());
        }
    }

    private void pollHub(String hubIp, List<Thermostat> thermostats) {
        List<ThermostatReading> readings;
        try {
            readings = hubClient.readThermostats(hubIp);
        } catch (Exception ex) {
            log.warn("Hub {} nicht erreichbar, {} Thermostate unavailable: {}", hubIp, thermostats.size(), ex.getMessage());
            markHubUnavailable(thermostats);
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, ThermostatReading> applied = cache.applyHubReadings(thermostats, readings, now);
        for (Thermostat thermostat : thermostats) {
            try {
                mirror(thermostat, applied.get(thermostat.getId()), now);
            } catch (Exception ex) {
                log.warn("Thermostat {} konnte nicht gespiegelt werden: {}", thermostat.getId(), ex.getMessage());
            }
        }
    }

    /** Historie zuerst: dieser Schritt kann nicht werfen, das Reporting steht in keinem eigenen try/catch. */
    private void mirror(Thermostat thermostat, ThermostatReading reading, LocalDateTime now) {
        historyRecorder.record(thermostat, reading, now);
        entityMapper.map(thermostat, reading, this::previousAttributes).forEach(entityStateService::reportState);
    }

    private void markHubUnavailable(List<Thermostat> thermostats) {
        cache.markOffline(thermostats);
        for (Thermostat thermostat : thermostats) {
            try {
                entityMapper.unavailable(thermostat, this::previousAttributes)
                        .forEach(entityStateService::reportState);
            } catch (Exception ex) {
                log.warn("Thermostat {} konnte nicht unavailable gemeldet werden: {}", thermostat.getId(), ex.getMessage());
            }
        }
    }

    private Map<String, Object> previousAttributes(String entityId) {
        try {
            return entityStateService.getByEntityId(entityId)
                    .map(state -> state.getAttributes())
                    .filter(json -> json != null && !json.isBlank())
                    .map(this::parseAttributes)
                    .orElse(Map.of());
        } catch (Exception ex) {
            log.debug("Attribute von {} nicht lesbar: {}", entityId, ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> parseAttributes(String json) {
        try {
            return objectMapper.readValue(json, ATTRIBUTE_MAP);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
