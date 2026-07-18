package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class EntityEventTriggerHandlerTest {

    private final EntityEventTriggerHandler handler = new EntityEventTriggerHandler();
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "t"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private EntityEventFired event(String action) {
        return new EntityEventFired("event.zigbee_flur_taster_action", action,
                Map.of("deviceClass", "button"), LocalDateTime.now());
    }

    private NodeConfig config(Map<String, Object> values) {
        return new NodeConfig(values);
    }

    @Test
    void firesOnEveryEventEvenWithSameAction() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action"));

        handler.onEntityEventFired(event("single"), cfg, ctx);
        handler.onEntityEventFired(event("single"), cfg, ctx);

        assertEquals(2, emitted.size());
        assertEquals("single", emitted.get(0).get("action"));
        assertEquals("event.zigbee_flur_taster_action", emitted.get(0).get("entityId"));
        assertEquals("t", emitted.get(0).get("triggerNodeId"));
    }

    @Test
    void actionFilterMatchesExactly() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action", "action", "double"));

        handler.onEntityEventFired(event("single"), cfg, ctx);
        assertTrue(emitted.isEmpty());

        handler.onEntityEventFired(event("double"), cfg, ctx);
        assertEquals(1, emitted.size());
    }

    @Test
    void blankFilterFiresForAnyAction() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action", "action", ""));

        handler.onEntityEventFired(event("hold"), cfg, ctx);

        assertEquals(1, emitted.size());
    }

    @Test
    void validateRequiresEntityId() {
        assertFalse(handler.validate(config(Map.of())).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e"))).isEmpty());
    }

    @Test
    void watchedEntityIdComesFromConfig() {
        assertEquals("e", handler.watchedEntityId(config(Map.of("entityId", "e"))).orElseThrow());
    }
}
