package com.household.manager.blink;

import static org.mockito.ArgumentMatchers.anyString;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.BlinkEntityMapper;
import com.household.manager.vision.VisionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlinkPollingServiceTest {

    private final SystemJobMonitor jobMonitor = mock(SystemJobMonitor.class);
    private final BlinkSidecarClient client = mock(BlinkSidecarClient.class);
    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final VisionProperties properties = new VisionProperties();
    private BlinkPollingService service;

    private static final SidecarCamera DOOR =
            new SidecarCamera("123", "Haustuer", "doorbell", true, "ok", "Zuhause", true);

    @BeforeEach
    void setUp() {
        service = new BlinkPollingService(properties, client, new BlinkEntityMapper(), entityStateService, jobMonitor);
    }

    @Test
    void meldetKameraUndSyncEntitaet() {
        when(client.listCameras(anyBoolean())).thenReturn(List.of(DOOR));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId)
                .containsExactlyInAnyOrder(
                        "binary_sensor.blink_123_armed",
                        "binary_sensor.blink_sync_zuhause_armed");
    }

    @Test
    void sidecarFehlerMarkiertZuletztGemeldeteUnavailableMitErhaltenenAttributen() {
        when(client.listCameras(anyBoolean())).thenReturn(List.of(DOOR));
        service.poll();
        clearInvocations(entityStateService);

        when(client.listCameras(anyBoolean())).thenThrow(new BlinkException("down"));
        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(update -> {
            assertThat(update.state()).isEqualTo("unavailable");
            assertThat(update.attributes()).isNotEmpty();
        });
    }

    @Test
    void nichtAngemeldetZaehltEbenfallsAlsAusfall() {
        when(client.listCameras(anyBoolean())).thenReturn(List.of(DOOR));
        service.poll();
        clearInvocations(entityStateService);

        when(client.listCameras(anyBoolean())).thenThrow(new IllegalStateException("nicht angemeldet"));
        service.poll();

        verify(entityStateService, times(2)).reportState(any());
    }

    @Test
    void deaktivierteIntegrationPolltNicht() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(client, entityStateService);
    }

    @Test
    void pollWirftNie() {
        when(client.listCameras(anyBoolean())).thenThrow(new RuntimeException("boom"));
        service.poll();
        // kein Throw = bestanden; ohne vorherige Updates gibt es nichts zu markieren
        verifyNoInteractions(entityStateService);
    }

    @Test
    void poll_notLoggedIn_reportsInactive() {
        when(client.listCameras(anyBoolean())).thenThrow(new IllegalStateException("Nicht bei Blink angemeldet"));

        service.poll();

        verify(jobMonitor).reportInactive(anyString());
        verify(jobMonitor, never()).reportFailure(anyString());
    }

    @Test
    void poll_sidecarError_reportsFailure() {
        when(client.listCameras(anyBoolean())).thenThrow(new RuntimeException("Sidecar down"));

        service.poll();

        verify(jobMonitor).reportFailure(anyString());
    }

    @Test
    void poll_disabled_reportsInactive() {
        properties.setEnabled(false);

        service.poll();

        verify(jobMonitor).reportInactive(anyString());
    }
}
