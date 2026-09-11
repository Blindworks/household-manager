package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimeConditionNodeHandlerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final FlowMessage MSG = FlowMessage.of(Map.of("entityId", "event.x"));

    private static TimeConditionNodeHandler at(String localTime) {
        var instant = LocalDate.of(2026, 9, 11).atTime(LocalTime.parse(localTime)).atZone(BERLIN).toInstant();
        return new TimeConditionNodeHandler(Clock.fixed(instant, BERLIN));
    }

    private static NodeConfig window(String from, String to) {
        return new NodeConfig(Map.of("from", from, "to", to));
    }

    private static int portOf(NodeResult result) {
        assertEquals(1, result.outputs().size());
        return result.outputs().keySet().iterator().next();
    }

    @Test
    void insideWindowEmitsOnTruePort() {
        NodeResult result = at("12:00").handle(MSG, window("05:00", "20:00"), null);
        assertEquals(0, portOf(result));
        assertEquals(List.of(MSG), result.outputs().get(0));
    }

    @Test
    void outsideWindowEmitsOnFalsePort() {
        assertEquals(1, portOf(at("21:00").handle(MSG, window("05:00", "20:00"), null)));
        assertEquals(1, portOf(at("04:30").handle(MSG, window("05:00", "20:00"), null)));
    }

    @Test
    void startInclusiveEndExclusive() {
        assertEquals(0, portOf(at("05:00").handle(MSG, window("05:00", "20:00"), null)));
        assertEquals(1, portOf(at("20:00").handle(MSG, window("05:00", "20:00"), null)));
    }

    @Test
    void windowOverMidnightIsTrueOnBothSides() {
        assertEquals(0, portOf(at("23:15").handle(MSG, window("22:00", "06:00"), null)));
        assertEquals(0, portOf(at("03:00").handle(MSG, window("22:00", "06:00"), null)));
        assertEquals(1, portOf(at("12:00").handle(MSG, window("22:00", "06:00"), null)));
    }

    @Test
    void evaluatesInClockZoneNotUtc() {
        // 19:30 Berlin = 17:30 UTC im September; in UTC laege das noch im Fenster 05:00-18:00.
        assertEquals(1, portOf(at("19:30").handle(MSG, window("05:00", "18:00"), null)));
    }

    @Test
    void validateAcceptsWellFormedWindow() {
        assertTrue(at("12:00").validate(window("05:00", "20:00")).isEmpty());
        assertTrue(at("12:00").validate(window("22:00", "06:00")).isEmpty());
    }

    @Test
    void validateRejectsMissingBounds() {
        List<String> errors = at("12:00").validate(NodeConfig.empty());
        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("from")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("to")));
    }

    @Test
    void validateRejectsUnparsableTime() {
        assertFalse(at("12:00").validate(window("5 Uhr", "20:00")).isEmpty());
        assertFalse(at("12:00").validate(window("05:00", "25:00")).isEmpty());
    }

    @Test
    void validateRejectsEmptyWindow() {
        List<String> errors = at("12:00").validate(window("08:00", "08:00"));
        assertEquals(1, errors.size());
    }

    @Test
    void catalogDescribesTwoStringFieldsAndTruthyFalsyPorts() {
        var h = at("12:00");
        assertEquals("time-condition", h.type());
        assertEquals(2, h.outputPorts());
        assertEquals(List.of("wahr", "falsch"), h.portLabels());
        assertEquals(2, h.fields().size());
        assertTrue(h.fields().stream().allMatch(f -> f.type() == NodeFieldType.STRING && f.required()));
    }
}
