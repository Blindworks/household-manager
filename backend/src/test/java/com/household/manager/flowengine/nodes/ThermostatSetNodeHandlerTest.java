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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T06:43:00Z"));

    @BeforeEach
    void setUp() {
        handler = new ThermostatSetNodeHandler(thermostatService, cache, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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
    void restoreForgetsTheTargetOnceTheGracePeriodIsOver() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");
        clock.advance(ThermostatSetNodeHandler.KEEP_AFTER_RESTORE.plusSeconds(1));

        run("restore");

        // Zweites Schliessen lange nach dem ersten: kein gemerkter Wert mehr, also der Rueckfall.
        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void reopeningRightAfterRestoreKeepsTheTargetAlthoughTheCacheStillShowsOff() {
        // Ablauf vom 2026-11-04: auf, zu, 6 s spaeter wieder auf, zu. Der Cache zeigt nach dem
        // Zurueckstellen noch das Aus-Ziel, weil das Thermostat den neuen Wert verzoegert meldet.
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");
        clock.advance(Duration.ofSeconds(6));
        run("off");
        clock.advance(Duration.ofMinutes(7));

        run("restore");

        verify(thermostatService, org.mockito.Mockito.times(2)).setTarget(BAD, 21.0);
        verify(thermostatService, never()).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void reopenedWindowKeepsTheTargetEvenIfClosingTakesLongerThanTheGracePeriod() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");
        clock.advance(Duration.ofMinutes(1));
        run("off");
        // Das Fenster bleibt lange offen: die Frist gilt nur zwischen Zurueckstellen und Oeffnen
        clock.advance(Duration.ofHours(1));

        run("restore");

        verify(thermostatService, org.mockito.Mockito.times(2)).setTarget(BAD, 21.0);
    }

    @Test
    void reopeningLongAfterRestoreWithStaleCacheFallsBackToManual() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");
        clock.advance(ThermostatSetNodeHandler.KEEP_AFTER_RESTORE.plusSeconds(1));
        run("off");

        run("restore");

        verify(thermostatService).setMode(BAD, ThermostatMode.MANUAL);
    }

    @Test
    void aFreshTargetAtReopeningWinsOverTheRememberedOne() {
        cached(21.0, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);
        run("restore");
        cached(22.5, ThermostatMode.MANUAL);
        run("off");
        cached(5.0, ThermostatMode.OFF);

        run("restore");

        verify(thermostatService).setTarget(BAD, 22.5);
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
