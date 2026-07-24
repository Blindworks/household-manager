package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetEntityStatesToolTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private EntityStateResponseMapper mapper;

    private EntityState entity(String entityId, String state) {
        EntityState e = new EntityState();
        e.setEntityId(entityId);
        e.setState(state);
        return e;
    }

    @Test
    void filtersByQueryOverEntityIdAndDisplayName() throws Exception {
        EntityState wohnzimmer = entity("sensor.zigbee_wz_temperature", "21.5");
        EntityState bad = entity("sensor.zigbee_bad_temperature", "23");
        when(entityStateService.find(isNull(), isNull())).thenReturn(List.of(wohnzimmer, bad));
        when(mapper.displayName(wohnzimmer)).thenReturn("Temperatur Wohnzimmer");
        when(mapper.displayName(bad)).thenReturn("Temperatur Bad");

        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);
        String result = tool.execute(Map.of("query", "wohnzimmer"));

        assertTrue(result.contains("Temperatur Wohnzimmer"));
        assertFalse(result.contains("Temperatur Bad"));
    }

    @Test
    void filtersByDomain() throws Exception {
        EntityState sensor = entity("sensor.x", "1");
        when(entityStateService.find(eq(EntityDomain.SENSOR), isNull())).thenReturn(List.of(sensor));
        lenient().when(mapper.displayName(sensor)).thenReturn("X");

        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);
        String result = tool.execute(Map.of("domain", "sensor"));

        assertTrue(result.contains("sensor.x"));
    }

    @Test
    void unknownDomainIsAHelpfulError() {
        GetEntityStatesTool tool = new GetEntityStatesTool(entityStateService, mapper);

        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("domain", "quatsch")));
        assertTrue(ex.getMessage().toLowerCase().contains("domain"));
    }
}
