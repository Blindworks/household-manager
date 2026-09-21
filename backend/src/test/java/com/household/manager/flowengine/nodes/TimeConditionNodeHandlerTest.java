package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunTimesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeConditionNodeHandlerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);
    private static final FlowMessage MSG = FlowMessage.of(Map.of("entityId", "event.x"));

    @Mock
    private SunTimesService sunTimes;

    private final List<String> debugLabels = new ArrayList<>();
    private final NodeContext ctx = new NodeContext() {
        public long flowId() { return 1L; }
        public String nodeId() { return "t"; }
        public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
        public void emit(int port, FlowMessage message) { }
        public TaskScheduler scheduler() { return null; }
        public void debug(String label, FlowMessage message) { debugLabels.add(label); }
    };

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    // dawn 06:20, sunrise 06:52, sunset 19:35, dusk 20:08
    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at(DAY, "06:20"), at(DAY, "06:52"), at(DAY, "19:35"), at(DAY, "20:08"));

    private TimeConditionNodeHandler at(String localTime) {
        var instant = at(DAY, localTime).toInstant();
        return new TimeConditionNodeHandler(Clock.fixed(instant, BERLIN), sunTimes);
    }

    private static NodeConfig window(String from, String to) {
        return new NodeConfig(Map.of("from", from, "to", to));
    }

    private static int portOf(NodeResult result) {
        assertEquals(1, result.outputs().size());
        return result.outputs().keySet().iterator().next();
    }

    // --- bestehendes Verhalten mit festen Uhrzeiten (unveraendert) ---

    @Test
    void insideWindowEmitsOnTruePort() {
        NodeResult result = at("12:00").handle(MSG, window("05:00", "20:00"), ctx);
        assertEquals(0, portOf(result));
        assertEquals(List.of(MSG), result.outputs().get(0));
    }

    @Test
    void outsideWindowEmitsOnFalsePort() {
        assertEquals(1, portOf(at("21:00").handle(MSG, window("05:00", "20:00"), ctx)));
        assertEquals(1, portOf(at("04:30").handle(MSG, window("05:00", "20:00"), ctx)));
    }

    @Test
    void startInclusiveEndExclusive() {
        assertEquals(0, portOf(at("05:00").handle(MSG, window("05:00", "20:00"), ctx)));
        assertEquals(1, portOf(at("20:00").handle(MSG, window("05:00", "20:00"), ctx)));
    }

    @Test
    void windowOverMidnightIsTrueOnBothSides() {
        assertEquals(0, portOf(at("23:15").handle(MSG, window("22:00", "06:00"), ctx)));
        assertEquals(0, portOf(at("03:00").handle(MSG, window("22:00", "06:00"), ctx)));
        assertEquals(1, portOf(at("12:00").handle(MSG, window("22:00", "06:00"), ctx)));
    }

    @Test
    void evaluatesInClockZoneNotUtc() {
        // 19:30 Berlin = 17:30 UTC im September; in UTC laege das noch im Fenster 05:00-18:00.
        assertEquals(1, portOf(at("19:30").handle(MSG, window("05:00", "18:00"), ctx)));
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

    // --- Sonnenausdruecke ---

    @Test
    void sunExpressionsResolveAgainstTodaysTimes() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        // Fenster "sunset-30" (19:05) bis "23:00"
        assertEquals(0, portOf(at("19:05").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(0, portOf(at("22:00").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(1, portOf(at("19:04").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(1, portOf(at("23:00").handle(MSG, window("sunset-30", "23:00"), ctx)));
    }

    @Test
    void darkWindowFromDuskToDawnSpansMidnight() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        assertEquals(0, portOf(at("20:08").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(0, portOf(at("02:00").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, portOf(at("06:20").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, portOf(at("12:00").handle(MSG, window("dusk", "dawn"), ctx)));
    }

    @Test
    void unresolvableSunExpressionIsFalseAndLeavesDebugTrace() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.empty());
        assertEquals(1, portOf(at("02:00").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, debugLabels.size());
        assertTrue(debugLabels.get(0).contains("Sonnenzeit"));
    }

    @Test
    void validateAcceptsSunExpressionsAndMixedWindows() {
        assertTrue(at("12:00").validate(window("dusk", "dawn")).isEmpty());
        assertTrue(at("12:00").validate(window("sunset-30", "23:00")).isEmpty());
        assertTrue(at("12:00").validate(window("07:00", "sunrise+60")).isEmpty());
    }

    @Test
    void validateRejectsBadSunExpressionsAndIdenticalExpressions() {
        assertFalse(at("12:00").validate(window("sunset -30", "23:00")).isEmpty());
        assertFalse(at("12:00").validate(window("sunset-300", "23:00")).isEmpty());
        assertFalse(at("12:00").validate(window("noon", "23:00")).isEmpty());
        assertEquals(1, at("12:00").validate(window("sunset", "sunset")).size());
        assertEquals(1, at("12:00").validate(window("sunset-30", "sunset-30")).size());
        assertEquals(1, at("12:00").validate(window("sunset-0", "sunset")).size());
    }

    @Test
    void mixedWindowWithFixedStartAndSunEndAtRuntime() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        // Fenster "07:00" bis "sunrise+60" (= 07:52)
        assertEquals(0, portOf(at("07:00").handle(MSG, window("07:00", "sunrise+60"), ctx)));
        assertEquals(0, portOf(at("07:51").handle(MSG, window("07:00", "sunrise+60"), ctx)));
        assertEquals(1, portOf(at("07:52").handle(MSG, window("07:00", "sunrise+60"), ctx)));
        assertEquals(1, portOf(at("06:59").handle(MSG, window("07:00", "sunrise+60"), ctx)));
    }
}
