package com.household.manager.laundry;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import com.household.manager.entitystate.EntityStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaundryTriggerListenerTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 11, 10, 0);

    private final LaundryProperties properties = new LaundryProperties();
    private final LaundryClimateReader climateReader = mock(LaundryClimateReader.class);
    private final LaundryDryingService service = mock(LaundryDryingService.class);
    private final MutableClock clock = new MutableClock(T0);
    private final LaundryTriggerListener listener =
            new LaundryTriggerListener(properties, climateReader, service, clock);

    @BeforeEach
    void climate() {
        when(climateReader.current()).thenReturn(Optional.of(new KellerClimate(18.0, 58.9, T0)));
    }

    @AfterEach
    void clearActor() {
        AuditActorContext.clear();
    }

    @Test
    void steckdoseAusFragtMitAktuellerKellerfeuchte() {
        listener.onStateChanged(event(properties.getSwitchEntity(), "on", "off"));

        verify(service).ask(LaundryTrigger.SWITCH_OFF, 58.9);
    }

    @Test
    void steckdoseAusDurchFlowFragtNicht() {
        AuditActorContext.set(AuditActor.flow(14));

        listener.onStateChanged(event(properties.getSwitchEntity(), "on", "off"));

        verify(service, never()).ask(any(), anyDouble());
    }

    @Test
    void steckdoseAusOhneKlimaFragtNicht() {
        when(climateReader.current()).thenReturn(Optional.empty());

        listener.onStateChanged(event(properties.getSwitchEntity(), "on", "off"));

        verify(service, never()).ask(any(), anyDouble());
    }

    @Test
    void fertigUndAnstiegUmDreiPunkteFragt() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));
        clock.advanceMinutes(120);

        listener.onStateChanged(event(properties.getHumidityEntity(), "60", "61.9"));

        verify(service).ask(LaundryTrigger.HUMIDITY_RISE, 58.9);
    }

    @Test
    void anstiegNachDreiStundenFragtNicht() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));
        clock.advanceMinutes(181);

        listener.onStateChanged(event(properties.getHumidityEntity(), "60", "65"));

        verify(service, never()).ask(any(), anyDouble());
    }

    @Test
    void anstiegUnterDreiPunktenFragtNicht() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));

        listener.onStateChanged(event(properties.getHumidityEntity(), "60", "61.8"));

        verify(service, never()).ask(any(), anyDouble());
    }

    @Test
    void fragtNachAnstiegNurEinmalProWaesche() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));
        listener.onStateChanged(event(properties.getHumidityEntity(), "60", "62"));
        listener.onStateChanged(event(properties.getHumidityEntity(), "62", "63"));

        verify(service).ask(LaundryTrigger.HUMIDITY_RISE, 58.9);
    }

    @Test
    void laeuftAnRuftOnNewWashUndVergisstFertig() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));

        listener.onStateChanged(event(properties.getRunningHelper(), "off", "on"));
        listener.onStateChanged(event(properties.getHumidityEntity(), "60", "65"));

        verify(service, org.mockito.Mockito.times(2)).onNewWash();   // fertig und laeuft
        verify(service, never()).ask(any(), anyDouble());
    }

    @Test
    void fertigHebtEineSperreDurchNeinAuf() {
        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));

        verify(service).onNewWash();
    }

    @Test
    void fertigHebtDieSperreAuchOhneKlimaAuf() {
        when(climateReader.current()).thenReturn(Optional.empty());

        listener.onStateChanged(event(properties.getFinishedHelper(), "off", "on"));

        verify(service).onNewWash();
    }

    @Test
    void ausgeschaltetIgnoriertEreignisse() {
        properties.setEnabled(false);

        listener.onStateChanged(event(properties.getSwitchEntity(), "on", "off"));

        verify(service, never()).ask(any(), anyDouble());
    }

    private EntityStateChangedEvent event(String entityId, String oldState, String newState) {
        return new EntityStateChangedEvent(entityId, oldState, newState, Map.of(), LocalDateTime.now(clock));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(LocalDateTime start) {
            this.now = start.atZone(ZONE).toInstant();
        }

        void advanceMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
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
}
