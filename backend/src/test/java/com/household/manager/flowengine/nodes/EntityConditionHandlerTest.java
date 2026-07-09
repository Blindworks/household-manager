package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityConditionHandlerTest {

    @Mock
    private EntityStateService entityStateService;

    private EntityConditionHandler handler() {
        return new EntityConditionHandler(entityStateService);
    }

    private final NodeConfig cfg = new NodeConfig(Map.of(
            "entityId", "sensor.weather_dwd_temperature", "operator", "<", "value", "5"));
    private final FlowMessage msg = FlowMessage.of(Map.of("v", "1"));

    @Test
    void routesToPort0WhenConditionTrue() {
        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature"))
                .thenReturn(Optional.of(EntityState.builder().state("3.2").build()));

        NodeResult result = handler().handle(msg, cfg, null);

        assertTrue(result.outputs().containsKey(0));
        assertFalse(result.outputs().containsKey(1));
    }

    @Test
    void routesToPort1WhenConditionFalseOrEntityMissing() {
        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature"))
                .thenReturn(Optional.of(EntityState.builder().state("12").build()));
        assertTrue(handler().handle(msg, cfg, null).outputs().containsKey(1));

        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature")).thenReturn(Optional.empty());
        assertTrue(handler().handle(msg, cfg, null).outputs().containsKey(1));
    }

    @Test
    void validateRequiresAllFields() {
        assertEquals(3, handler().validate(NodeConfig.empty()).size());
        assertTrue(handler().validate(cfg).isEmpty());
    }
}
