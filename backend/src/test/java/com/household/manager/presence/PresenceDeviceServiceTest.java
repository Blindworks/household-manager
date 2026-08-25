package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceDeviceServiceTest {

    @Mock
    private PresenceDeviceRepository repository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceMonitor monitor;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private PresenceDeviceService service;

    private PresenceDtos.DeviceRequest request(Long userId, String name, String host) {
        return new PresenceDtos.DeviceRequest(userId, name, host, true);
    }

    @Test
    void createLegtGeraetAnUndAuditiert() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> {
            PresenceDevice device = inv.getArgument(0);
            device.setId(1L);
            return device;
        });

        PresenceDtos.DeviceAdminResponse response =
                service.create(request(5L, " iPhone Benedikt ", " 192.168.1.50 "));

        assertThat(response.name()).isEqualTo("iPhone Benedikt");
        assertThat(response.host()).isEqualTo("192.168.1.50");
        assertThat(response.userId()).isEqualTo(5L);
        verify(auditService).record(eq("presence.device.create"), anyString());
    }

    @Test
    void createLehntUnbekanntenBenutzerAb() {
        when(userRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(request(99L, "iPhone", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntLeereFelderAb() {
        assertThatThrownBy(() -> service.create(request(5L, " ", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request(null, "iPhone", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fehlendesActiveGiltAlsAktiv() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PresenceDtos.DeviceAdminResponse response = service.create(
                new PresenceDtos.DeviceRequest(5L, "iPhone", "192.168.1.50", null));

        assertThat(response.active()).isTrue();
    }

    @Test
    void deleteEntferntGeraetMonitorEintragUndAuditiert() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));

        service.delete(7L);

        verify(repository).delete(device);
        verify(monitor).remove(7L);
        verify(auditService).record(eq("presence.device.delete"), anyString());
    }

    @Test
    void updateUnbekannterIdWirft404() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(42L, request(5L, "iPhone", "192.168.1.50")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateGeaendertHostLoeschtMonitorEintrag() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, request(5L, "iPhone", "192.168.1.99"));

        verify(monitor).remove(7L);
    }

    @Test
    void updateOhneHostAenderungBehaeltMonitorEintrag() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, request(5L, "iPhone Neu", "192.168.1.50"));

        verify(monitor, never()).remove(any());
    }

    @Test
    void whitespaceImGespeichertenHostZaehltNichtAlsAenderung() {
        // Der gespeicherte Host traegt Rand-Leerzeichen (nur ueber einen Schreibweg
        // ausserhalb dieses Service erreichbar, z. B. ein direktes DB-Update) - der
        // Vergleich muss trotzdem "gleich" erkennen, sonst startet eine neue
        // Probezeit fuer eine Adresse, die sich gar nicht geaendert hat.
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host(" 192.168.1.50 ").active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, request(5L, "iPhone Neu", "192.168.1.50"));

        verify(monitor, never()).remove(any());
    }
}
