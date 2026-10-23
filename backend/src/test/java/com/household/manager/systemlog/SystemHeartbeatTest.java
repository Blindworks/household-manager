package com.household.manager.systemlog;

import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");

    @Mock
    private ApplicationSettingsService settings;

    private SystemHeartbeat heartbeat() {
        return new SystemHeartbeat(settings, Clock.fixed(NOW, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void beat_writesCurrentInstant() {
        heartbeat().beat();

        verify(settings).saveSetting("SYSTEM_LOG", "last_alive_at", NOW.toString());
    }

    @Test
    void beat_neverThrows() {
        doThrow(new IllegalStateException("db")).when(settings).saveSetting(anyString(), anyString(), anyString());

        heartbeat().beat();
    }

    @Test
    void shutdown_beatsOnce_andNeverThrows() {
        SystemHeartbeat heartbeat = heartbeat();

        heartbeat.beatOnShutdown();
        verify(settings).saveSetting("SYSTEM_LOG", "last_alive_at", NOW.toString());

        doThrow(new IllegalStateException("db")).when(settings).saveSetting(anyString(), anyString(), anyString());
        heartbeat.beatOnShutdown();
    }

    @Test
    void lastAliveBeforeStart_isSnapshotTakenBeforeFirstBeat() {
        Instant previous = NOW.minusSeconds(3600);
        when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", previous.toString()));
        SystemHeartbeat heartbeat = heartbeat();
        heartbeat.snapshotLastAlive();

        lenient().when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", NOW.toString()));
        heartbeat.beat();

        assertEquals(Optional.of(previous), heartbeat.lastAliveBeforeStart());
    }

    @Test
    void springLifecycle_snapshotsOnCreation_andBeatsOnClose() {
        Instant previous = NOW.minusSeconds(3600);
        when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", previous.toString()));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ApplicationSettingsService.class, () -> settings);
        context.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneId.of("Europe/Berlin")));
        context.registerBean(SystemHeartbeat.class);
        context.refresh();
        SystemHeartbeat heartbeat = context.getBean(SystemHeartbeat.class);

        lenient().when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", NOW.toString()));
        assertEquals(Optional.of(previous), heartbeat.lastAliveBeforeStart());

        context.close();
        verify(settings).saveSetting("SYSTEM_LOG", "last_alive_at", NOW.toString());
    }

    @Test
    void lastAlive_readsStoredInstant() {
        when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", NOW.toString()));

        assertEquals(Optional.of(NOW), heartbeat().lastAlive());
    }

    @Test
    void lastAlive_unreadable_returnsEmpty() {
        when(settings.getSettingsByCategory("SYSTEM_LOG")).thenReturn(Map.of("last_alive_at", "kaputt"));
        assertTrue(heartbeat().lastAlive().isEmpty());

        when(settings.getSettingsByCategory("SYSTEM_LOG")).thenThrow(new IllegalStateException("db"));
        assertTrue(heartbeat().lastAlive().isEmpty());
    }
}
