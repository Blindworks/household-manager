package com.household.manager.rainradar;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RainRadarPollingServiceTest {

    private static final Instant MEASURED = Instant.parse("2026-11-17T13:25:00Z");
    private static final Clock CLOCK = Clock.fixed(MEASURED.plusSeconds(360), ZoneId.of("Europe/Berlin"));

    private RainRadarClient client;
    private TractiveHomeSettingsService homeSettings;
    private EntityStateService entityStates;
    private EntityStateResponseMapper responseMapper;
    private SystemJobMonitor jobMonitor;
    private RainRadarProperties properties;
    private RainRadarPollingService service;

    @BeforeEach
    void setUp() {
        client = mock(RainRadarClient.class);
        homeSettings = mock(TractiveHomeSettingsService.class);
        entityStates = mock(EntityStateService.class);
        responseMapper = mock(EntityStateResponseMapper.class);
        jobMonitor = mock(SystemJobMonitor.class);
        properties = new RainRadarProperties();
        when(homeSettings.getSettings()).thenReturn(home(50.05, 8.6));
        service = new RainRadarPollingService(client, properties, homeSettings, entityStates, responseMapper,
                jobMonitor, CLOCK);
    }

    private static TractiveHomeSettings home(Double lat, Double lon) {
        return new TractiveHomeSettings(lat, lon, 100, 200, 60, 50, "Zuhause");
    }

    private static List<RainRadarFrame> raining(Instant measuredAt) {
        return List.of(new RainRadarFrame(measuredAt, 2.04, false),
                new RainRadarFrame(measuredAt.plusSeconds(300), 1.0, true));
    }

    private List<EntityStateUpdate> reported() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStates, org.mockito.Mockito.atLeastOnce()).reportState(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void meldetDreiEntitaetenMitLokalerMesszeit() throws Exception {
        when(client.fetch(50.05, 8.6, CLOCK.instant())).thenReturn(raining(MEASURED));

        service.poll();

        List<EntityStateUpdate> updates = reported();
        assertThat(updates).extracting(EntityStateUpdate::entityId).containsExactly(
                RainRadarPollingService.RAINING_ENTITY_ID, RainRadarPollingService.SOON_ENTITY_ID,
                RainRadarPollingService.INTENSITY_ENTITY_ID);
        assertThat(updates).extracting(EntityStateUpdate::state).containsExactly("on", "on", "2.04");
        assertThat(updates.get(0).attributes()).containsEntry("measuredAt", "2026-11-17T14:25:00")
                .doesNotContainKey("expectedRainAt").doesNotContainKey("deviceClass");
        assertThat(updates.get(2).attributes()).containsEntry("unit", "mm/h");
    }

    @Test
    void einzelnerFehlabrufAendertNichts() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), any())).thenThrow(new IOException("weg"));

        service.poll();
        service.poll();

        verify(entityStates, never()).reportState(any());
        verify(jobMonitor, times(2)).reportFailure(anyString());
    }

    @Test
    void nachDreiFehlabrufenUnavailableMitErhaltenenAttributen() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), any())).thenThrow(new IOException("weg"));
        EntityState stored = EntityState.builder().entityId(RainRadarPollingService.RAINING_ENTITY_ID)
                .domain(EntityDomain.BINARY_SENSOR).friendlyName("Regen am Haus").state("off")
                .attributes("{\"mmPerHour\":0.0}").build();
        when(entityStates.getByEntityId(anyString())).thenReturn(Optional.empty());
        when(entityStates.getByEntityId(RainRadarPollingService.RAINING_ENTITY_ID)).thenReturn(Optional.of(stored));
        when(responseMapper.parseAttributes("{\"mmPerHour\":0.0}")).thenReturn(Map.of("mmPerHour", 0.0));

        service.poll();
        service.poll();
        service.poll();

        List<EntityStateUpdate> updates = reported();
        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.entityId()).isEqualTo(RainRadarPollingService.RAINING_ENTITY_ID);
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsEntry("mmPerHour", 0.0);
        });
    }

    @Test
    void erfolgNachFehlernSetztDenZaehlerZurueck() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), any()))
                .thenThrow(new IOException("weg"))
                .thenThrow(new IOException("weg"))
                .thenReturn(raining(MEASURED))
                .thenThrow(new IOException("weg"));

        service.poll();
        service.poll();
        service.poll();
        service.poll();

        verify(entityStates, never()).getByEntityId(anyString());
    }

    @Test
    void veralteteMessungZaehltAlsFehlabruf() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), any())).thenReturn(raining(MEASURED.minusSeconds(3600)));

        service.poll();

        verify(entityStates, never()).reportState(any());
        verify(jobMonitor).reportFailure(org.mockito.ArgumentMatchers.contains("Minuten alt"));
    }

    @Test
    void ohneZuhauseInaktivUndKeinAbruf() throws Exception {
        when(homeSettings.getSettings()).thenReturn(home(null, null));

        service.poll();

        verify(client, never()).fetch(anyDouble(), anyDouble(), any());
        verify(jobMonitor).reportInactive(anyString());
    }

    @Test
    void abgeschaltetInaktiv() throws Exception {
        properties.setEnabled(false);

        service.poll();

        verify(client, never()).fetch(anyDouble(), anyDouble(), any());
        verify(jobMonitor).reportInactive(eq("Regenradar deaktiviert"));
    }
}
