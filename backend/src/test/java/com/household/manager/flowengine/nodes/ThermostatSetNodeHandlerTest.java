package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import com.household.manager.thermostat.ThermostatService;
import com.household.manager.thermostat.ThermostatStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatSetNodeHandlerTest {

    private static final long BAD = 1L;

    @Mock
    private ThermostatService thermostatService;

    @Mock
    private ThermostatStateCache cache;

    private ThermostatSetNodeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ThermostatSetNodeHandler(thermostatService, cache);
    }

    private static NodeConfig config(Object... keyValues) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new NodeConfig(values);
    }

    private void cached(Double target, ThermostatMode mode) {
        ThermostatReading reading = new ThermostatReading("child", "Bad", true, 20.0, target, mode, false, false);
        when(cache.find(BAD)).thenReturn(Optional.of(new ThermostatStateCache.Entry(reading, LocalDateTime.now())));
    }

    private void run(String action) {
        handler.handle(FlowMessage.of(Map.of()), config("thermostatId", BAD, "action", action), null);
    }

    @Test
    void offSwitchesTheThermostatOffAndPassesTheMessageThrough() {
        cached(21.0, ThermostatMode.MANUAL);

        NodeResult result = handler.handle(FlowMessage.of(Map.of("foo", "bar")),
                config("thermostatId", BAD, "action", "off"), null);

        verify(thermostatService).setMode(BAD, ThermostatMode.OFF);
        assertEquals("bar", result.outputs().get(0).get(0).get("foo"));
    }

    @Test
    void restoreBringsBackTheTargetThatWasActiveBeforeOff() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");

        cached(5.0, ThermostatMode.OFF);
        run("restore");

        verify(thermostatService).setTarget(BAD, 21.0);
    }

    @Test
    void restoreForgetsTheTargetAfterUse() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");

        run("restore");

        // Zweites Schliessen ohne erneutes Oeffnen: kein gemerkter Wert mehr, also der Rueckfall.
        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void secondOffWhileAlreadyOffKeepsTheOriginalTarget() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("off");

        run("restore");

        // Sonst ueberschriebe das zweite Oeffnen die 21 mit den 5 Grad des Aus-Zustands.
        verify(thermostatService).setTarget(BAD, 21.0);
    }

    @Test
    void restoreLeavesAThermostatAloneThatSomeoneTurnedBackOnByHand() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");

        cached(23.0, ThermostatMode.MANUAL);
        run("restore");

        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
        verify(thermostatService, never()).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void restoreWithoutRememberedTargetFallsBackToManualMode() {
        cached(5.0, ThermostatMode.OFF);

        run("restore");

        // Nach einem Neustart ist der gemerkte Wert weg; MANUAL setzt dann den Standard-Sollwert.
        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void offWithUnknownTargetRemembersNothing() {
        when(cache.find(BAD)).thenReturn(Optional.empty());
        run("off");

        cached(5.0, ThermostatMode.OFF);
        run("restore");

        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
    }

    @Test
    void restoreWithUnknownCurrentStateStillRestoresTheRememberedTarget() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");

        when(cache.find(BAD)).thenReturn(Optional.empty());
        run("restore");

        verify(thermostatService).setTarget(BAD, 21.0);
    }

    @Test
    void rememberedTargetsAreKeptPerThermostat() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        ThermostatReading other = new ThermostatReading("c2", "Kueche", true, 18.0, 18.5, ThermostatMode.MANUAL, false, false);
        when(cache.find(4L)).thenReturn(Optional.of(new ThermostatStateCache.Entry(other, LocalDateTime.now())));
        handler.handle(FlowMessage.of(Map.of()), config("thermostatId", 4, "action", "off"), null);

        cached(5.0, ThermostatMode.OFF);
        run("restore");

        verify(thermostatService).setTarget(BAD, 21.0);
    }

    @Test
    void targetSetsTheConfiguredTemperature() {
        handler.handle(FlowMessage.of(Map.of()),
                config("thermostatId", BAD, "action", "target", "targetCelsius", 19.5), null);

        verify(thermostatService).setTarget(BAD, 19.5);
    }

    @Test
    void deviceErrorBreaksTheBranchInsteadOfBeingSwallowed() {
        cached(21.0, ThermostatMode.MANUAL);
        doThrow(new TapoException("Hub nicht erreichbar")).when(thermostatService).setMode(BAD, ThermostatMode.OFF);

        assertThrows(TapoException.class, () -> run("off"));
    }

    @Test
    void failedOffDoesNotRememberATarget() {
        cached(21.0, ThermostatMode.MANUAL);
        doThrow(new TapoException("Hub nicht erreichbar")).when(thermostatService).setMode(BAD, ThermostatMode.OFF);
        assertThrows(TapoException.class, () -> run("off"));

        cached(5.0, ThermostatMode.OFF);
        run("restore");

        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void validationRejectsMissingIdAndUnknownAction() {
        List<String> errors = handler.validate(config("action", "aus"));

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("thermostatId")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")));
    }

    @Test
    void validationRejectsNonPositiveOrFractionalIds() {
        assertEquals(1, handler.validate(config("thermostatId", 0, "action", "off")).size());
        assertEquals(1, handler.validate(config("thermostatId", 1.5, "action", "off")).size());
        assertEquals(1, handler.validate(config("thermostatId", "abc", "action", "off")).size());
    }

    @Test
    void validationRequiresAValidTargetOnlyForTheTargetAction() {
        assertEquals(1, handler.validate(config("thermostatId", BAD, "action", "target")).size());
        assertEquals(1, handler.validate(config("thermostatId", BAD, "action", "target", "targetCelsius", 21.3)).size());
        assertEquals(1, handler.validate(config("thermostatId", BAD, "action", "target", "targetCelsius", 31)).size());
        assertEquals(1, handler.validate(config("thermostatId", BAD, "action", "target", "targetCelsius", "NaN")).size());
        assertTrue(handler.validate(config("thermostatId", BAD, "action", "target", "targetCelsius", "21.5")).isEmpty());
        assertTrue(handler.validate(config("thermostatId", BAD, "action", "off")).isEmpty());
        assertTrue(handler.validate(config("thermostatId", "1", "action", "restore")).isEmpty());
    }

    @Test
    void validationDoesNotTouchTheThermostats() {
        handler.validate(config("thermostatId", BAD, "action", "off"));

        // Ein Deploy darf nicht daran scheitern, dass der Hub gerade nicht erreichbar ist.
        verifyNoInteractions(thermostatService, cache);
    }

    @Test
    void exposesTypeFieldsAndSinglePort() {
        assertEquals("thermostat-set", handler.type());
        assertEquals(1, handler.outputPorts());
        assertEquals(List.of("Ausgang"), handler.portLabels());
        assertEquals(NodeFieldType.NUMBER, handler.fields().get(0).type());
        assertTrue(handler.fields().get(0).required());
        assertEquals(List.of("off", "restore", "target"), handler.fields().get(1).options());
        assertEquals(NodeFieldType.NUMBER, handler.fields().get(2).type());
    }
}
