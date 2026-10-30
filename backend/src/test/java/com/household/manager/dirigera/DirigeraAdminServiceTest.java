package com.household.manager.dirigera;

import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraDtos.HubView;
import com.household.manager.dirigera.DirigeraEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirigeraAdminServiceTest {

    private static final String RELATION_ID = "abc-123";

    private DirigeraSensorRepository sensorRepository;
    private EntityTileVisibilityRepository tileRepository;
    private EntityStateService entityStateService;
    private AuditService auditService;
    private DirigeraAdminService service;

    @BeforeEach
    void setUp() {
        sensorRepository = mock(DirigeraSensorRepository.class);
        tileRepository = mock(EntityTileVisibilityRepository.class);
        entityStateService = mock(EntityStateService.class);
        auditService = mock(AuditService.class);
        service = new DirigeraAdminService(mock(DirigeraSettingsService.class), mock(DirigeraPairingService.class),
                mock(DirigeraPollingService.class), sensorRepository, tileRepository, entityStateService,
                auditService);
    }

    @Test
    void loeschenEinesBekanntenSensorsWirft409() {
        when(sensorRepository.findById(1L)).thenReturn(Optional.of(sensor(true)));

        assertThatThrownBy(() -> service.deleteSensor(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(sensorRepository, never()).delete(any());
        verify(entityStateService, never()).deleteByEntityId(any());
    }

    @Test
    void loeschenEntferntEntitaetenTileRegelnUndZeile() {
        DirigeraSensor sensor = sensor(false);
        when(sensorRepository.findById(1L)).thenReturn(Optional.of(sensor));
        List<String> entityIds = Arrays.stream(Measurement.values())
                .map(m -> DirigeraEntityMapper.entityId(RELATION_ID, m)).toList();
        List<EntityTileVisibility> rules = List.of(new EntityTileVisibility());
        when(tileRepository.findByEntityIdIn(entityIds)).thenReturn(rules);

        service.deleteSensor(1L);

        verify(tileRepository).deleteAll(rules);
        entityIds.forEach(id -> verify(entityStateService).deleteByEntityId(id));
        verify(sensorRepository).delete(sensor);
        verify(auditService).record("dirigera.sensor.delete", "Wohnzimmer");
    }

    @Test
    void hubViewEnthaeltKeinenToken() {
        List<String> components = Arrays.stream(HubView.class.getRecordComponents())
                .map(c -> c.getName()).toList();

        assertThat(components).doesNotContain("accessToken", "certSha256");
    }

    private static DirigeraSensor sensor(boolean knownToHub) {
        return DirigeraSensor.builder().id(1L).relationId(RELATION_ID).name("Wohnzimmer")
                .knownToHub(knownToHub).build();
    }
}
