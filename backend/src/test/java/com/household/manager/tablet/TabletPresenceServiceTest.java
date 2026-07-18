package com.household.manager.tablet;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class TabletPresenceServiceTest {

    /** Testuhr, die sich manuell vorstellen lässt. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-18T10:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Mock
    private EntityStateService entityStateService;

    private final MutableClock clock = new MutableClock();
    private TabletPresenceService service;

    @BeforeEach
    void setUp() {
        service = new TabletPresenceService(entityStateService, clock);
    }

    private EntityStateUpdate lastReportedUpdate(int expectedCalls) {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(expectedCalls)).reportState(captor.capture());
        return captor.getValue();
    }

    @Test
    void presenceOnIsMirroredAsBinarySensorOn() {
        service.reportPresence("wandtablet", true);

        EntityStateUpdate update = lastReportedUpdate(1);
        assertEquals("binary_sensor.tablet_wandtablet_presence", update.entityId());
        assertEquals("on", update.state());
        assertEquals("wandtablet", update.sourceRef());
    }

    @Test
    void presenceOffIsMirroredAsBinarySensorOff() {
        service.reportPresence("wandtablet", false);

        assertEquals("off", lastReportedUpdate(1).state());
    }

    @Test
    void staleTabletIsMarkedUnavailable() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);

        service.markStaleTabletsUnavailable();

        assertEquals("unavailable", lastReportedUpdate(2).state());
    }

    @Test
    void freshTabletStaysAvailable() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(60);

        service.markStaleTabletsUnavailable();

        verify(entityStateService, times(1)).reportState(org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(entityStateService);
    }

    @Test
    void unavailableIsOnlyReportedOnce() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);

        service.markStaleTabletsUnavailable();
        service.markStaleTabletsUnavailable();

        verify(entityStateService, times(2)).reportState(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newReportRevivesUnavailableTablet() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);
        service.markStaleTabletsUnavailable();

        service.reportPresence("wandtablet", true);

        assertEquals("on", lastReportedUpdate(3).state());
    }
}
