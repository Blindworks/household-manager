package com.household.manager.tesla;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tesla.dto.TeslaStatusResponse;
import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeslaVehicleServiceTest {

    private static final String VIN = "5YJ3E7EB2NF000001";

    private final TeslaProperties properties = new TeslaProperties();
    private final TeslaSettingsService settings = mock(TeslaSettingsService.class);
    private final TessieApiClient client = mock(TessieApiClient.class);
    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final TractiveHomeSettingsService home = mock(TractiveHomeSettingsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneId.of("Europe/Berlin"));

    private TeslaVehicleService service;

    @BeforeEach
    void setUp() {
        service = new TeslaVehicleService(properties, settings, client, new TeslaEntityMapper(),
                entityStateService, home, audit, clock);
        when(settings.current()).thenReturn(new TeslaSettings("token", null, 5));
        when(client.listVehicles("token")).thenReturn(List.of(new TessieVehicle(VIN, "Blitz")));
        when(home.getSettings()).thenReturn(new TractiveHomeSettings(50.18, 8.73, 50, 300, 60, 40, "Zuhause"));
    }

    @Test
    void ohneTokenNichtKonfiguriert() {
        when(settings.current()).thenReturn(new TeslaSettings(null, null, 5));

        assertThat(service.status().configured()).isFalse();
        assertThatThrownBy(service::refresh).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abrufMeldetEntitaetenUndZuhause() {
        when(client.getState("token", VIN)).thenReturn(snapshot(true));

        service.refresh();

        TeslaStatusResponse status = service.status();
        assertThat(status.vehicle().batteryLevel()).isEqualTo(55);
        assertThat(status.vehicle().atHome()).isTrue();
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId).contains(
                "sensor.tesla_5yj3e7eb2nf000001_battery",
                "sensor.tesla_5yj3e7eb2nf000001_range",
                "sensor.tesla_5yj3e7eb2nf000001_charging",
                "lock.tesla_5yj3e7eb2nf000001");
    }

    @Test
    void fehlerBehaeltLetztenStandUndMeldetUnavailable() {
        when(client.getState("token", VIN)).thenReturn(snapshot(true))
                .thenThrow(new TeslaException("Tessie nicht erreichbar"));
        service.refresh();

        assertThatThrownBy(service::refresh).isInstanceOf(TeslaException.class);

        TeslaStatusResponse status = service.status();
        assertThat(status.vehicle()).isNotNull();
        assertThat(status.error()).contains("nicht erreichbar");
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(u -> "unavailable".equals(u.state()))
                .allSatisfy(u -> assertThat(u.attributes()).isNotEmpty())
                .hasSize(4);
    }

    @Test
    void befehlWirdAusgefuehrtAuditiertUndNachgeladen() {
        when(client.getState("token", VIN)).thenReturn(snapshot(false));

        service.execute(TeslaCommand.UNLOCK);

        var order = inOrder(client, audit);
        order.verify(client).sendCommand("token", VIN, TeslaCommand.UNLOCK);
        order.verify(audit).record(eq("tesla.command"), any());
        order.verify(client).getState("token", VIN);
    }

    @Test
    void scheitertNurDasNachladenGiltDerBefehlTrotzdem() {
        when(client.getState("token", VIN)).thenThrow(new TeslaException("weg"));

        TeslaStatusResponse status = service.execute(TeslaCommand.START_CLIMATE);

        assertThat(status.configured()).isTrue();
        verify(audit).record(eq("tesla.command"), any());
    }

    @Test
    void intervallBestimmtFaelligkeit() {
        when(client.getState("token", VIN)).thenReturn(snapshot(true));
        assertThat(service.isDue()).isTrue();

        service.refresh();

        assertThat(service.isDue()).isFalse();
    }

    private static TeslaVehicleSnapshot snapshot(boolean locked) {
        return new TeslaVehicleSnapshot(VIN, "Blitz", "online", 55, 275.7, 80, "Charging", true, 11.0, 95,
                false, null, true, locked, false, false, 6.5, 2.0, 50.1801, 8.7301, null, "model3",
                "PearlWhite", null, null, Instant.parse("2026-11-27T07:59:00Z"));
    }
}
