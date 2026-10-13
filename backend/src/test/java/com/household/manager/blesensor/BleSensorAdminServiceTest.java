package com.household.manager.blesensor;

import com.household.manager.audit.AuditService;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorAdminServiceTest {

    @Mock private BleSensorRepository repository;
    @Mock private EntityStateService entityStateService;
    @Mock private AuditService auditService;

    private BleSensorAdminService service;

    @BeforeEach
    void setUp() {
        service = new BleSensorAdminService(repository, new BleSensorEntityMapper(), entityStateService, auditService);
    }

    private BleSensor sensor(boolean active, String name) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name(name).active(active)
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
    }

    @Test
    void listeLiefertNichtUebernommeneZuerst() {
        BleSensor unadopted = BleSensor.builder().id(1L).mac("11:22:33:44:55:66").active(false).build();
        when(repository.findAllByOrderByActiveAscNameAscMacAsc()).thenReturn(List.of(unadopted, sensor(true, "Küche")));

        var list = service.list();

        assertThat(list).extracting(r -> r.mac()).containsExactly("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff");
    }

    @Test
    void uebernehmenSetztNamenUndAktivUndAuditiert() {
        BleSensor s = sensor(false, null);
        when(repository.findById(7L)).thenReturn(Optional.of(s));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(7L, new SensorUpdateRequest("  Küche ", true));

        assertThat(response.name()).isEqualTo("Küche");
        assertThat(response.active()).isTrue();
        verify(auditService).record(eq("ble-sensor.update"), any());
    }

    @Test
    void aktivierenOhneNamenIstVierhundert() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, null)));

        assertThatThrownBy(() -> service.update(7L, new SensorUpdateRequest("  ", true)))
                .isInstanceOf(IllegalArgumentException.class);   // Review Focus 5
        verify(repository, never()).save(any());
    }

    @Test
    void fehlendesActiveGiltAlsAktiv() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, null)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(7L, new SensorUpdateRequest("Bad", null)).active()).isTrue();
    }

    @Test
    void deaktivierenMeldetEinmalUnavailable() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, new SensorUpdateRequest("Küche", false));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allMatch(u -> "unavailable".equals(u.state()));   // Review Focus 2
    }

    @Test
    void umbenennenEinesAktivenMeldetKeinUnavailable() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, new SensorUpdateRequest("Esszimmer", true));

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void loeschenEinesAktivenIstKonflikt() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));

        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(repository, never()).delete(any());
    }

    @Test
    void loeschenEinesInaktivenEntferntZeileUndEntitaeten() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, "Küche")));

        service.delete(7L);

        verify(repository).delete(any());
        verify(entityStateService).deleteByEntityId("sensor.ble_aabbccddeeff_temperature");
        verify(entityStateService).deleteByEntityId("sensor.ble_aabbccddeeff_humidity");
        verify(auditService).record(eq("ble-sensor.delete"), any());
    }

    @Test
    void unbekannteIdIstVierhundertvier() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
