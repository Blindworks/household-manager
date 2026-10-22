package com.household.manager.systemlog;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.entitystate.EntityStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EntityEventLogListenerTest {

    private final SystemLogWriter writer = mock(SystemLogWriter.class);
    private final EntityEventLogListener listener = new EntityEventLogListener(writer);

    private SystemLogEvent logged() {
        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    private static EntityStateChangedEvent change(String old, String now) {
        return new EntityStateChangedEvent("sensor.x", old, now, Map.of(), LocalDateTime.now());
    }

    @Test
    void numericToNumeric_isSkipped() {
        listener.onStateChanged(change("21.5", "21.6"));

        verify(writer, never()).write(any());
    }

    @Test
    void numericToUnavailable_isLoggedAsWarn() {
        listener.onStateChanged(change("21.5", "unavailable"));

        SystemLogEvent event = logged();
        assertThat(event.status()).isEqualTo(SystemLogStatus.WARN);
        assertThat(event.kind()).isEqualTo(SystemLogKind.ENTITY);
        assertThat(event.message()).isEqualTo("21.5 → unavailable");
    }

    @Test
    void onOff_isLogged() {
        listener.onStateChanged(change("off", "on"));

        SystemLogEvent event = logged();
        assertThat(event.status()).isEqualTo(SystemLogStatus.INFO);
        assertThat(event.source()).isEqualTo("sensor.x");
        assertThat(event.title()).isEqualTo("sensor.x");
        assertThat(event.message()).isEqualTo("off → on");
    }

    @Test
    void sunPhase_isLogged() {
        listener.onStateChanged(change("day", "dusk"));

        assertThat(logged().message()).isEqualTo("day → dusk");
    }

    @Test
    void eventFired_isLoggedWithAction() {
        listener.onEventFired(new EntityEventFired("event.taster", "single", Map.of(), LocalDateTime.now()));

        SystemLogEvent event = logged();
        assertThat(event.message()).isEqualTo("single");
        assertThat(event.source()).isEqualTo("event.taster");
        assertThat(event.status()).isEqualTo(SystemLogStatus.INFO);
    }

    @Test
    void nullOldState_isLogged() {
        listener.onStateChanged(change(null, "on"));

        assertThat(logged().message()).isEqualTo("neu: on");
    }

    @Test
    void occurredAt_isTakenFromTheEvents() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 22, 7, 0, 1);
        LocalDateTime firedAt = LocalDateTime.of(2026, 10, 22, 7, 0, 2);

        listener.onStateChanged(new EntityStateChangedEvent("sensor.x", "off", "on", Map.of(), changedAt));
        listener.onEventFired(new EntityEventFired("event.taster", "single", Map.of(), firedAt));

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, times(2)).write(captor.capture());
        assertThat(captor.getAllValues()).extracting(SystemLogEvent::occurredAt).containsExactly(changedAt, firedAt);
    }

    @Test
    void isNumericChange_requiresBothNumeric() {
        assertThat(EntityEventLogListener.isNumericChange(" 1 ", "2.5")).isTrue();
        assertThat(EntityEventLogListener.isNumericChange(null, "2")).isFalse();
        assertThat(EntityEventLogListener.isNumericChange("2", null)).isFalse();
        assertThat(EntityEventLogListener.isNumericChange("on", "2")).isFalse();
    }

    @Test
    void writerFailure_isSwallowed() {
        doThrow(new RuntimeException("x")).when(writer).write(any());

        listener.onStateChanged(change("off", "on"));
    }
}
