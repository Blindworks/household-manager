package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityStateTriggerHandlerTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private TaskScheduler taskScheduler;

    private EntityStateTriggerHandler handler;
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new java.util.ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        handler = new EntityStateTriggerHandler(entityStateService);
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "t"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private NodeConfig config(Map<String, Object> values) {
        return new NodeConfig(values);
    }

    private EntityStateChangedEvent event(String oldState, String newState) {
        return new EntityStateChangedEvent("sensor.x", oldState, newState, Map.of("unit", "W"), LocalDateTime.now());
    }

    @Test
    void firesOnTransitionIntoRange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5"));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        assertEquals(1, emitted.size());
        assertEquals("sensor.x", emitted.get(0).get("entityId"));
        assertEquals("4", emitted.get(0).get("newState"));
    }

    @Test
    void doesNotRefireWhileStayingInRange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5"));

        handler.onEntityEvent(event("4", "3"), cfg, ctx);

        assertTrue(emitted.isEmpty());
    }

    @Test
    void changedOperatorFiresOnEveryChange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "changed"));

        handler.onEntityEvent(event("on", "off"), cfg, ctx);
        handler.onEntityEvent(event("off", "on"), cfg, ctx);

        assertEquals(2, emitted.size());
    }

    @Test
    void forSecondsSchedulesTimerInsteadOfFiring() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        assertTrue(emitted.isEmpty());
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void timerFiresOnlyIfConditionStillHolds() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> timerTask = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(timerTask.capture(), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        // Bedingung gilt noch -> feuert
        EntityState current = EntityState.builder().entityId("sensor.x").state("3").build();
        when(entityStateService.getByEntityId("sensor.x")).thenReturn(Optional.of(current));
        timerTask.getValue().run();
        assertEquals(1, emitted.size());

        // Bedingung gilt nicht mehr -> feuert nicht
        emitted.clear();
        handler.onEntityEvent(event("10", "4"), cfg, ctx);
        when(entityStateService.getByEntityId("sensor.x"))
                .thenReturn(Optional.of(EntityState.builder().entityId("sensor.x").state("50").build()));
        timerTask.getValue().run();
        assertTrue(emitted.isEmpty());
    }

    @Test
    void leavingRangeCancelsPendingTimer() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);   // Timer startet
        handler.onEntityEvent(event("4", "50"), cfg, ctx);   // verlässt Bereich

        verify(future).cancel(false);
        assertTrue(emitted.isEmpty());
    }

    @Test
    void validateRequiresEntityIdOperatorAndValueForNumericOps() {
        assertFalse(handler.validate(config(Map.of())).isEmpty());
        assertFalse(handler.validate(config(Map.of("entityId", "e", "operator", "<"))).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e", "operator", "changed"))).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e", "operator", "<", "value", "5"))).isEmpty());
    }

    @Test
    void feuertBeimWiederanlaufenAusUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "==",
                "value", "on"));

        handler.onEntityEvent(event("unavailable", "on"), config, ctx);

        assertEquals(1, emitted.size(),
                "Ein verschluckter Schwellenalarm beim Wiederanlaufen wiegt schwerer "
                        + "als eine doppelte Meldung, deshalb feuert dieser Uebergang normal");
    }

    @Test
    void feuertNichtBeimAusfallNachUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "changed"));

        handler.onEntityEvent(event("on", "unavailable"), config, ctx);

        assertTrue(emitted.isEmpty(),
                "Der Ausfall selbst darf kein Ereignis sein");
    }

    @Test
    void feuertBeiChangedAusUnavailable() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "changed"));

        handler.onEntityEvent(event("unavailable", "off"), config, ctx);

        assertEquals(1, emitted.size(),
                "Auch 'changed' feuert beim Wiederanlaufen wieder normal");
    }

    @Test
    void feuertWeiterhinBeiEchtemZustandswechsel() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "==",
                "value", "on"));

        handler.onEntityEvent(event("off", "on"), config, ctx);

        assertEquals(1, emitted.size(),
                "Ein echter Wechsel muss unveraendert feuern");
    }

    @Test
    void feuertBeiUebergangAusUnknown() {
        NodeConfig config = config(Map.of(
                "entityId", "binary_sensor.zigbee_eingangstuer_contact",
                "operator", "==",
                "value", "on"));

        handler.onEntityEvent(event("unknown", "on"), config, ctx);

        assertEquals(1, emitted.size(),
                "'unknown' ist nicht 'unavailable' und darf nicht mit unterdrueckt werden");
    }

    @Test
    void wechselNachUnavailableStorniertLaufendenTimer() {
        NodeConfig config = config(Map.of(
                "entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), config, ctx);           // Timer startet
        handler.onEntityEvent(event("4", "unavailable"), config, ctx);  // Ausfall

        verify(future).cancel(false);
        assertTrue(emitted.isEmpty());
    }
}
