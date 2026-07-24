package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fragt Entitätszustände ab — Temperaturen, Sensoren, Präsenz, Luftqualität usw. */
@Component
@RequiredArgsConstructor
public class GetEntityStatesTool implements AgentTool {

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_entity_states";
    }

    @Override
    public String description() {
        return "Fragt Zustaende aller Entitaeten ab (Temperaturen, Sensoren, Praesenz, "
                + "Luftqualitaet, Schloesser). Optional filterbar nach domain und "
                + "Suchbegriff (query, matcht Name und Entity-ID).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "domain", Map.of("type", "string",
                                "description", "Optional: z.B. sensor, switch, light, lock, binary_sensor"),
                        "query", Map.of("type", "string",
                                "description", "Optional: Suchbegriff, z.B. 'wohnzimmer'")));
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        EntityDomain domain = parseDomain(input.get("domain"));
        String query = input.get("query") != null
                ? String.valueOf(input.get("query")).toLowerCase(Locale.ROOT) : null;

        List<Map<String, Object>> states = entityStateService.find(domain, null).stream()
                .filter(entity -> matches(entity, query))
                .map(entity -> Map.<String, Object>of(
                        "entityId", entity.getEntityId(),
                        "name", mapper.displayName(entity),
                        "state", String.valueOf(entity.getState())))
                .toList();
        return json.writeValueAsString(states);
    }

    private boolean matches(EntityState entity, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return entity.getEntityId().toLowerCase(Locale.ROOT).contains(query)
                || mapper.displayName(entity).toLowerCase(Locale.ROOT).contains(query);
    }

    private EntityDomain parseDomain(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            return EntityDomain.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unbekannte domain '" + raw + "'. Gueltig: "
                    + Arrays.toString(EntityDomain.values()));
        }
    }
}
