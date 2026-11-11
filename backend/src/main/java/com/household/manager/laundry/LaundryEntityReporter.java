package com.household.manager.laundry;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Spiegelt den Waschgang als {@code sensor.laundry_drying}; bewusst ohne deviceClass. */
@Component
@RequiredArgsConstructor
public class LaundryEntityReporter {

    public static final String ENTITY_ID = "sensor.laundry_drying";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final EntityStateService entityStateService;

    public void report(LaundryStateView view) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putTime(attributes, "predictedDryAt", view.predictedDryAt());
        putTime(attributes, "startedAt", view.startedAt());
        attributes.put("estimatePaused", view.estimatePaused());
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.LAUNDRY)
                .sourceRef("keller")
                .friendlyName("Wäschetrocknung")
                .state(view.status())
                .attributes(attributes)
                .build());
    }

    /** Ein fehlender Wert laesst den Schluessel weg, statt null zu tragen. */
    private static void putTime(Map<String, Object> attributes, String key, LocalDateTime value) {
        if (value != null) {
            attributes.put(key, value.format(TIME_FORMAT));
        }
    }
}
