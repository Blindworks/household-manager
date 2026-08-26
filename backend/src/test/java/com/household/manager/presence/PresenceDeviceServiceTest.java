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

import java.util.List;
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
    void listeLiefertGeraeteInAufsteigenderIdReihenfolge() {
        PresenceDevice first = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        PresenceDevice second = PresenceDevice.builder().id(2L).userId(6L)
                .name("Pixel").host("192.168.1.51").active(false).build();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(first, second));

        List<PresenceDtos.DeviceAdminResponse> result = service.list();

        assertThat(result).extracting(PresenceDtos.DeviceAdminResponse::id)
                .containsExactly(1L, 2L);
        assertThat(result).extracting(PresenceDtos.DeviceAdminResponse::active)
                .containsExactly(true, false);
    }

    @Test
    void createEntferntMonitorEintragFuerDieNeueId() {
        // AUTO_INCREMENT kann nach einem Neustart eine geloeschte Id neu vergeben
        // (siehe PresenceMonitor.remove Javadoc) - ein Waisen-Eintrag im Monitor
        // duerfte dem neuen Geraet sonst ein falsches PRESENT vererben.
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> {
            PresenceDevice device = inv.getArgument(0);
            device.setId(3L);
            return device;
        });

        service.create(request(5L, "iPhone", "192.168.1.50"));

        verify(monitor).remove(3L);
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
    void createLehntLeerenNamenAb() {
        assertThatThrownBy(() -> service.create(request(5L, " ", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntLeeresHostAb() {
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntFehlendeUserIdAb() {
        assertThatThrownBy(() -> service.create(request(null, "iPhone", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Grenzwert exakt am Rand: ein vertauschtes {@code >} zu {@code >=} in der
     * Laengenpruefung wuerde von den "zu lang"-Tests unten nicht zuverlaessig
     * entdeckt (101 Zeichen scheitern bei beiden Varianten), faellt hier aber
     * garantiert auf.
     */
    @Test
    void createAkzeptiertNamenMitGenau100Zeichen() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String exactly100 = "x".repeat(100);

        PresenceDtos.DeviceAdminResponse response = service.create(request(5L, exactly100, "192.168.1.50"));

        assertThat(response.name()).hasSize(100);
    }

    @Test
    void createAkzeptiertHostMitGenau255Zeichen() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String exactly255 = "1".repeat(255);

        PresenceDtos.DeviceAdminResponse response = service.create(request(5L, "iPhone", exactly255));

        assertThat(response.host()).hasSize(255);
    }

    @Test
    void createLehntZuLangenNamenAb() {
        String tooLong = "x".repeat(101);
        assertThatThrownBy(() -> service.create(request(5L, tooLong, "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void createLehntZuLangenHostAb() {
        String tooLong = "1".repeat(256);
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntHostMitLeerzeichenImInnernAb() {
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", "192.168. 1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntHostMitEingebettetemZeilenumbruchAb() {
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", "192.168.1.50\n.5")))
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
    void updateAuditiert() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, request(5L, "iPhone Neu", "192.168.1.50"));

        verify(auditService).record(eq("presence.device.update"), anyString());
    }

    @Test
    void updateLehntZuLangenNamenAb() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));
        String tooLong = "x".repeat(101);
        assertThatThrownBy(() -> service.update(7L, request(5L, tooLong, "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
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
