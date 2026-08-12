package com.household.manager.service;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spiegelt die Lüftungsempfehlung als {@code binary_sensor.insight_ventilation}
 * in den Entity-State-Layer, damit Flows auf die on-Flanke triggern können.
 * Ohne frischen Außenwert wird {@code unavailable} gemeldet; die Flow-Engine
 * unterdrückt den Übergang NACH unavailable engine-weit, es entsteht also kein
 * Fehltrigger (die !=-Falle aus CLAUDE.md gilt hier wie überall).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VentilationEntityReporter {

    private final VentilationRecommendationService recommendationService;
    private final EntityStateService entityStateService;

    @Scheduled(fixedDelayString = "${ventilation.report-interval-ms:300000}",
            initialDelayString = "${ventilation.initial-delay-ms:60000}")
    public void report() {
        try {
            VentilationAssessment assessment = recommendationService.assess();
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(
                            EntityDomain.BINARY_SENSOR, EntitySource.INSIGHT, "ventilation", null))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.INSIGHT)
                    .sourceRef("ventilation")
                    .friendlyName("Lüftungsempfehlung")
                    .state(stateOf(assessment))
                    .attributes(attributesOf(assessment))
                    .build());
        } catch (Exception ex) {
            log.warn("Lüftungsbewertung fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private String stateOf(VentilationAssessment assessment) {
        if (assessment.recommended() == null) {
            return "unavailable";
        }
        return assessment.recommended() ? "on" : "off";
    }

    private Map<String, Object> attributesOf(VentilationAssessment assessment) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", "ventilation");
        if (assessment.outdoorTemperature() != null) {
            attributes.put("outdoorTemperature", assessment.outdoorTemperature());
        }
        List<Map<String, Object>> rooms = assessment.rooms().stream()
                .map(this::roomAttributes)
                .toList();
        attributes.put("rooms", rooms);
        return attributes;
    }

    private Map<String, Object> roomAttributes(VentilationRoom room) {
        return Map.of("name", room.name(), "temperature", room.temperature());
    }
}
