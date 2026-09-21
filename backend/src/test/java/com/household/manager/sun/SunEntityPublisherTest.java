package com.household.manager.sun;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SunEntityPublisherTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Mock
    private SunTimesService sunTimes;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private EntityStateResponseMapper responseMapper;

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static SunDayTimes timesOf(LocalDate day) {
        return new SunDayTimes(day, at(day, "06:35"), at(day, "07:07"), at(day, "19:10"), at(day, "19:42"));
    }

    private SunEntityPublisher publisherAt(String time) {
        Clock clock = Clock.fixed(at(TODAY, time).toInstant(), BERLIN);
        return new SunEntityPublisher(sunTimes, entityStateService, responseMapper, clock);
    }

    private EntityStateUpdate reported() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        return captor.getValue();
    }

    @BeforeEach
    void stubTimes() {
        lenient().when(sunTimes.timesFor(TODAY)).thenReturn(Optional.of(timesOf(TODAY)));
        lenient().when(sunTimes.timesFor(TOMORROW)).thenReturn(Optional.of(timesOf(TOMORROW)));
        lenient().when(sunTimes.elevationAt(any())).thenReturn(Optional.of(31.26));
    }

    @Test
    void reportsPhaseAndTodayTimesAsAttributes() {
        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("sensor.sun", update.entityId());
        assertEquals(EntityDomain.SENSOR, update.domain());
        assertEquals(EntitySource.SUN, update.source());
        assertEquals("day", update.state());
        assertEquals("06:35", update.attributes().get("dawn"));
        assertEquals("07:07", update.attributes().get("sunrise"));
        assertEquals("19:10", update.attributes().get("sunset"));
        assertEquals("19:42", update.attributes().get("dusk"));
        assertEquals(31.3, update.attributes().get("elevation"));
        assertFalse(update.attributes().containsKey("deviceClass"));
    }

    @Test
    void nextEventsPointToTodayWhileStillAheadOtherwiseTomorrow() {
        publisherAt("12:00").publish();

        Map<String, Object> attrs = reported().attributes();
        assertEquals("2026-09-22T07:07:00", attrs.get("nextSunrise"));
        assertEquals("2026-09-21T19:10:00", attrs.get("nextSunset"));
    }

    @Test
    void atExactSunsetStateIsDuskAndNextSunsetIsTomorrow() {
        publisherAt("19:10").publish();

        EntityStateUpdate update = reported();
        assertEquals("dusk", update.state());
        assertEquals("2026-09-22T19:10:00", update.attributes().get("nextSunset"));
    }

    @Test
    void atNightBothNextEventsPointToTomorrow() {
        publisherAt("23:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("night", update.state());
        assertEquals("2026-09-22T07:07:00", update.attributes().get("nextSunrise"));
        assertEquals("2026-09-22T19:10:00", update.attributes().get("nextSunset"));
    }

    @Test
    void withoutTomorrowTheNextKeysAreOmitted() {
        when(sunTimes.timesFor(TOMORROW)).thenReturn(Optional.empty());

        publisherAt("23:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("night", update.state());
        assertFalse(update.attributes().containsKey("nextSunrise"));
        assertFalse(update.attributes().containsKey("nextSunset"));
        assertEquals("19:10", update.attributes().get("sunset"));
    }

    @Test
    void withoutElevationTheKeyIsOmitted() {
        when(sunTimes.elevationAt(any())).thenReturn(Optional.empty());

        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("day", update.state());
        assertFalse(update.attributes().containsKey("elevation"));
    }

    @Test
    void phaseFollowsTheClock() {
        publisherAt("19:20").publish();
        assertEquals("dusk", reported().state());
    }

    @Test
    void withoutHomeReportsUnavailableAndKeepsStoredAttributes() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        EntityState existing = new EntityState();
        existing.setEntityId("sensor.sun");
        existing.setState("day");
        existing.setAttributes("{\"sunrise\":\"07:07\"}");
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.of(existing));
        when(responseMapper.parseAttributes("{\"sunrise\":\"07:07\"}")).thenReturn(Map.of("sunrise", "07:07"));

        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("unavailable", update.state());
        assertEquals("07:07", update.attributes().get("sunrise"));
    }

    @Test
    void withoutHomeAndWithoutEntityCreatesItAsUnavailable() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.empty());

        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("unavailable", update.state());
        assertTrue(update.attributes().isEmpty());
    }

    @Test
    void alreadyUnavailableIsNotReportedAgain() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        EntityState existing = new EntityState();
        existing.setEntityId("sensor.sun");
        existing.setState("unavailable");
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.of(existing));

        publisherAt("12:00").publish();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void publishNeverThrows() {
        when(sunTimes.timesFor(TODAY)).thenThrow(new IllegalStateException("kaputt"));
        assertDoesNotThrow(() -> publisherAt("12:00").publish());
        verify(entityStateService, never()).reportState(any());
    }
}
