package com.household.manager.blaulicht;

import com.household.manager.blaulicht.dto.BlaulichtBriefingDto;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entitäten von „Blaulicht Bad Vilbel“ (alle {@code EntitySource.BLAULICHT}, ohne {@code deviceClass}):
 * <ul>
 *   <li>{@code sensor.blaulicht_overnight} — State = Fälle in Bad Vilbel in der Nacht, Attribute Briefing- und
 *       Nachtragstext und Zähler. Wird nie {@code unavailable}.</li>
 *   <li>{@code event.blaulicht_morning_briefing} — feuert zum Nachtende, Aktion = Briefingtext.</li>
 *   <li>{@code event.blaulicht_addendum} — feuert zur Nachtragszeit, nur wenn es einen Nachtrag gibt.</li>
 *   <li>{@code event.blaulicht_nina_warning} — feuert je neuer NINA-Warnung, Aktion = Überschrift.</li>
 * </ul>
 * Die Benachrichtigungen selbst sind Flows (Platzhalter {@code {action}}). Wirft nie.
 */
@Component
@Slf4j
public class BlaulichtEntityReporter {

    public static final String OVERNIGHT_ENTITY_ID = "sensor.blaulicht_overnight";
    public static final String MORNING_ENTITY_ID = "event.blaulicht_morning_briefing";
    public static final String ADDENDUM_ENTITY_ID = "event.blaulicht_addendum";
    public static final String NINA_ENTITY_ID = "event.blaulicht_nina_warning";
    /** Die Spalte {@code entity_states.state} fasst 255 Zeichen. */
    static final int ACTION_MAX = 255;

    private final EntityStateService entityStateService;

    public BlaulichtEntityReporter(EntityStateService entityStateService) {
        this.entityStateService = entityStateService;
    }

    public void reportOverview(BlaulichtBriefingDto briefing) {
        try {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("text", briefing.text());
            attributes.put("nearbyCount", briefing.nearbyCount());
            attributes.put("olderNewCount", briefing.olderNewCount());
            attributes.put("addendumCount", briefing.addendumCount());
            if (!briefing.addendumText().isEmpty()) {
                attributes.put("addendumText", briefing.addendumText());
            }
            attributes.put("nightStart", briefing.nightStart().toString());
            attributes.put("nightEnd", briefing.nightEnd().toString());
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(OVERNIGHT_ENTITY_ID)
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.BLAULICHT)
                    .sourceRef("overnight")
                    .friendlyName("Blaulicht: Fälle heute Nacht")
                    .state(String.valueOf(briefing.mainCount()))
                    .attributes(attributes)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Blaulicht: Übersicht nicht gemeldet: {}", e.getMessage());
        }
    }

    public void fireMorningBriefing(BlaulichtBriefingDto briefing) {
        fire(MORNING_ENTITY_ID, "Blaulicht: Morgen-Briefing", briefing.text(),
                Map.of("mainCount", briefing.mainCount(), "nearbyCount", briefing.nearbyCount()));
    }

    public void fireAddendum(BlaulichtBriefingDto briefing) {
        fire(ADDENDUM_ENTITY_ID, "Blaulicht: Nachtrag", briefing.addendumText(),
                Map.of("addendumCount", briefing.addendumCount()));
    }

    public void fireNinaWarning(BlaulichtEvent warning) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", warning.getTitle());
        if (warning.getUrl() != null) {
            attributes.put("url", warning.getUrl());
        }
        attributes.put("publishedAt", warning.getPublishedAt().toString());
        fire(NINA_ENTITY_ID, "Blaulicht: NINA-Warnung", "Warnung für " + warning.getLocality() + ": "
                + warning.getTitle(), attributes);
    }

    private void fire(String entityId, String name, String action, Map<String, Object> attributes) {
        try {
            entityStateService.reportEvent(EntityStateUpdate.builder()
                    .entityId(entityId)
                    .domain(EntityDomain.EVENT)
                    .source(EntitySource.BLAULICHT)
                    .sourceRef(entityId.substring(entityId.indexOf('.') + 1))
                    .friendlyName(name)
                    .state(truncate(action))
                    .attributes(attributes)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Blaulicht: Ereignis {} nicht gemeldet: {}", entityId, e.getMessage());
        }
    }

    static String truncate(String text) {
        if (text.length() <= ACTION_MAX) {
            return text;
        }
        return text.substring(0, ACTION_MAX - 1).stripTrailing() + "…";
    }
}
