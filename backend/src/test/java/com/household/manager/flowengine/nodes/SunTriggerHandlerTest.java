package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunTimesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
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
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SunTriggerHandlerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @Mock
    private SunTimesService sunTimes;
    @Mock
    private TaskScheduler taskScheduler;

    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new ArrayList<>();
    private final ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
    private final ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
    private ScheduledFuture<?> future;
    private NodeContext ctx;

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static SunDayTimes timesOf(LocalDate day) {
        return new SunDayTimes(day, at(day, "06:35"), at(day, "07:07"), at(day, "19:10"), at(day, "19:42"));
    }

    private SunTriggerHandler handlerAt(String time) {
        return new SunTriggerHandler(sunTimes, Clock.fixed(at(TODAY, time).toInstant(), BERLIN));
    }

    private static NodeConfig config(String event, Object offset) {
        return offset == null ? new NodeConfig(Map.of("event", event))
                : new NodeConfig(Map.of("event", event, "offsetMinutes", offset));
    }

    @BeforeEach
    void setUp() {
        future = mock(ScheduledFuture.class);
        ctx = new NodeContext() {
            public long flowId() { return 7L; }
            public String nodeId() { return "sun1"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
        lenient().when(sunTimes.timesFor(any(LocalDate.class)))
                .thenAnswer(inv -> Optional.of(timesOf(inv.getArgument(0))));
        lenient().doReturn(future).when(taskScheduler).schedule(tasks.capture(), instants.capture());
    }

    @Test
    void schedulesTodaysEventWhenStillAhead() {
        handlerAt("12:00").register(config("sunset", null), ctx);
        assertEquals(at(TODAY, "19:10").toInstant(), instants.getValue());
    }

    @Test
    void appliesNegativeOffset() {
        handlerAt("12:00").register(config("sunset", -30), ctx);
        assertEquals(at(TODAY, "18:40").toInstant(), instants.getValue());
    }

    @Test
    void offsetMayArriveAsString() {
        handlerAt("12:00").register(config("dusk", "15"), ctx);
        assertEquals(at(TODAY, "19:57").toInstant(), instants.getValue());
    }

    @Test
    void rollsOverToTomorrowWhenTodaysEventIsPast() {
        handlerAt("20:00").register(config("sunset", null), ctx);
        assertEquals(at(TODAY.plusDays(1), "19:10").toInstant(), instants.getValue());
    }

    @Test
    void negativeOffsetThatIsAlreadyPastRollsOverToo() {
        // 18:50 liegt zwischen sunset-30 (18:40) und sunset (19:10): heute ist vorbei, morgen zaehlt.
        handlerAt("18:50").register(config("sunset", -30), ctx);
        assertEquals(at(TODAY.plusDays(1), "18:40").toInstant(), instants.getValue());
    }

    @Test
    void firingEmitsMessageAndReschedulesForTheNextDay() {
        handlerAt("12:00").register(config("sunrise", null), ctx);
        // Registrierung um 12:00: Aufgang heute ist vorbei -> morgen 07:07.
        assertEquals(at(TODAY.plusDays(1), "07:07").toInstant(), instants.getValue());

        tasks.getValue().run();

        assertEquals(1, emitted.size());
        FlowMessage msg = emitted.get(0);
        assertEquals("sunrise", msg.get("sunEvent"));
        assertEquals(0, msg.get("offsetMinutes"));
        assertEquals("sun1", msg.get("triggerNodeId"));
        assertEquals(at(TODAY.plusDays(1), "07:07").toLocalDateTime(), msg.get("scheduledFor"));
        // Neu geplant fuer uebermorgen — nicht erneut fuer den gerade gefeuerten Zeitpunkt.
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        assertEquals(at(TODAY.plusDays(2), "07:07").toInstant(), instants.getValue());
    }

    @Test
    void cleanupCancelsFutureAndPreventsRescheduling() {
        Runnable cleanup = handlerAt("12:00").register(config("sunset", null), ctx);

        cleanup.run();
        verify(future).cancel(false);

        tasks.getValue().run();
        assertTrue(emitted.isEmpty());
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void withoutHomeRetriesInAnHourInsteadOfFailing() {
        when(sunTimes.timesFor(any(LocalDate.class))).thenReturn(Optional.empty());
        SunTriggerHandler handler = handlerAt("12:00");

        assertDoesNotThrow(() -> handler.register(config("sunset", null), ctx));
        assertEquals(at(TODAY, "13:00").toInstant(), instants.getValue());
        assertTrue(emitted.isEmpty());

        // Beim Wiederholungsversuch ist das Zuhause da -> echtes Ereignis geplant.
        when(sunTimes.timesFor(any(LocalDate.class))).thenAnswer(inv -> Optional.of(timesOf(inv.getArgument(0))));
        tasks.getValue().run();
        assertEquals(at(TODAY, "19:10").toInstant(), instants.getValue());
        assertTrue(emitted.isEmpty());
    }

    @Test
    void validateAcceptsEventWithAndWithoutOffset() {
        SunTriggerHandler h = handlerAt("12:00");
        assertTrue(h.validate(config("sunset", null)).isEmpty());
        assertTrue(h.validate(config("dawn", -240)).isEmpty());
        assertTrue(h.validate(config("dusk", "240")).isEmpty());
    }

    @Test
    void validateRejectsMissingOrUnknownEvent() {
        SunTriggerHandler h = handlerAt("12:00");
        assertFalse(h.validate(NodeConfig.empty()).isEmpty());
        assertFalse(h.validate(config("noon", null)).isEmpty());
    }

    @Test
    void validateRejectsOffsetOutOfRangeOrNonInteger() {
        SunTriggerHandler h = handlerAt("12:00");
        assertFalse(h.validate(config("sunset", 241)).isEmpty());
        assertFalse(h.validate(config("sunset", -3000)).isEmpty());
        assertFalse(h.validate(config("sunset", "halb")).isEmpty());
        assertFalse(h.validate(config("sunset", 1.5)).isEmpty());
    }

    @Test
    void watchesNoEntity() {
        assertTrue(handlerAt("12:00").watchedEntityId(NodeConfig.empty()).isEmpty());
    }
}
