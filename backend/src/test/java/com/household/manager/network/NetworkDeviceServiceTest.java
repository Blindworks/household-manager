package com.household.manager.network;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.repository.NetworkDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkDeviceServiceTest {

    @Mock
    private NetworkDeviceRepository repository;
    @Mock
    private NetworkDeviceStatusMonitor monitor;
    @Mock
    private AuditService auditService;

    private NetworkDeviceService service;

    @BeforeEach
    void setUp() {
        service = new NetworkDeviceService(repository, monitor, auditService);
    }

    private NetworkDevice existing() {
        return NetworkDevice.builder()
                .id(3L).name("Router").host("192.168.1.1").tcpPort(80)
                .sortOrder(1).active(true).build();
    }

    @Test
    void listetAlleGeraeteInDerSortierungDesRepositories() {
        NetworkDevice device = existing();
        when(repository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));

        List<NetworkDtos.DeviceAdminResponse> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(3L);
        assertThat(result.get(0).name()).isEqualTo("Router");
        assertThat(result.get(0).host()).isEqualTo("192.168.1.1");
        assertThat(result.get(0).tcpPort()).isEqualTo(80);
        assertThat(result.get(0).sortOrder()).isEqualTo(1);
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void legtEinGeraetAnUndSchreibtEinAuditDetail() {
        when(repository.save(any())).thenAnswer(call -> {
            NetworkDevice device = call.getArgument(0);
            device.setId(42L);
            return device;
        });

        NetworkDtos.DeviceAdminResponse response = service.create(
                new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", 22, 3, true));

        assertThat(response.id()).isEqualTo(42L);
        ArgumentCaptor<NetworkDevice> captor = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Switch");
        assertThat(captor.getValue().getHost()).isEqualTo("192.168.1.2");
        assertThat(captor.getValue().getTcpPort()).isEqualTo(22);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("network.device.create"), detail.capture());
        assertThat(detail.getValue()).contains("Switch").contains("192.168.1.2");
    }

    @Test
    void trimmtNameUndHostBeimAnlegen() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new NetworkDtos.DeviceRequest("  Switch  ", "  192.168.1.2  ", null, null, null));

        ArgumentCaptor<NetworkDevice> captor = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Switch");
        assertThat(captor.getValue().getHost()).isEqualTo("192.168.1.2");
    }

    @Test
    void setztSortOrderAufNullWennFehlend() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", null, null, true));

        ArgumentCaptor<NetworkDevice> captor = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSortOrder()).isEqualTo(0);
    }

    @Test
    void setztActiveAufTrueWennFehlend() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", null, 1, null));

        ArgumentCaptor<NetworkDevice> captor = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void erlaubtEinFehlendesTcpPort() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", null, 1, true));

        ArgumentCaptor<NetworkDevice> captor = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTcpPort()).isNull();
    }

    @Test
    void weistEinenFehlendenNamenAbOhneZuSpeichern() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest(null, "192.168.1.2", null, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), any());
    }

    @Test
    void weistEinenLeerenNamenAb() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest("   ", "192.168.1.2", null, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
        verify(repository, never()).save(any());
    }

    @Test
    void weistEinenFehlendenHostAb() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest("Switch", null, null, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host");
        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), any());
    }

    @Test
    void weistEinenLeerenHostAb() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest("Switch", "   ", null, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host");
        verify(repository, never()).save(any());
    }

    @Test
    void weistEinenZuNiedrigenPortAb() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", 0, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");
        verify(repository, never()).save(any());
    }

    @Test
    void weistEinenZuHohenPortAb() {
        assertThatThrownBy(() -> service.create(
                new NetworkDtos.DeviceRequest("Switch", "192.168.1.2", 65536, 1, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");
        verify(repository, never()).save(any());
    }

    @Test
    void aktualisiertEinBestehendesGeraetUndSchreibtEinAudit() {
        NetworkDevice device = existing();
        when(repository.findById(3L)).thenReturn(Optional.of(device));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        NetworkDtos.DeviceAdminResponse response = service.update(3L,
                new NetworkDtos.DeviceRequest("Neuer Name", "192.168.1.9", 443, 5, false));

        assertThat(response.name()).isEqualTo("Neuer Name");
        assertThat(response.host()).isEqualTo("192.168.1.9");
        assertThat(response.tcpPort()).isEqualTo(443);
        assertThat(response.sortOrder()).isEqualTo(5);
        assertThat(response.active()).isFalse();
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("network.device.update"), any());
    }

    /** Verhalten wie bei den Kalender-Kategorien: fehlendes Feld gilt als aktiv. */
    @Test
    void setztActiveBeimUpdateAufTrueWennFehlend() {
        NetworkDevice device = existing();
        device.setActive(false);
        when(repository.findById(3L)).thenReturn(Optional.of(device));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        NetworkDtos.DeviceAdminResponse response = service.update(3L,
                new NetworkDtos.DeviceRequest("Router", "192.168.1.1", 80, 1, null));

        assertThat(response.active()).isTrue();
    }

    @Test
    void weistEinUpdateMitUngueltigemPortAb() {
        assertThatThrownBy(() -> service.update(3L,
                new NetworkDtos.DeviceRequest("Router", "192.168.1.1", 70000, 1, true)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void wirftBeimUpdateEinerUnbekanntenIdEine404Exception() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L,
                new NetworkDtos.DeviceRequest("Router", "192.168.1.1", 80, 1, true)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void loeschtEinGeraetUndBenachrichtigtDenMonitor() {
        when(repository.findById(3L)).thenReturn(Optional.of(existing()));

        service.delete(3L);

        ArgumentCaptor<NetworkDevice> deleted = ArgumentCaptor.forClass(NetworkDevice.class);
        verify(repository).delete(deleted.capture());
        assertThat(deleted.getValue().getId()).isEqualTo(3L);
        verify(monitor).remove(3L);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("network.device.delete"), detail.capture());
        assertThat(detail.getValue()).contains("Router").contains("192.168.1.1");
    }

    @Test
    void wirftBeimLoeschenEinerUnbekanntenIdEine404Exception() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any(NetworkDevice.class));
        verify(monitor, never()).remove(anyLong());
    }
}
