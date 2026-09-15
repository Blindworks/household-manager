package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.zigbee.dto.FlowReferenceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeFlowReferenceServiceTest {

    @Mock private EntityStateRepository entityStateRepository;
    @Mock private FlowRepository flowRepository;
    @InjectMocks private ZigbeeFlowReferenceService service;

    private static EntityState entity(String id, String ref) {
        return EntityState.builder().entityId(id).domain(EntityDomain.SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(ref).friendlyName(id).state("1").build();
    }

    private static Flow flow(long id, String name, boolean enabled, String draft, String deployed) {
        return Flow.builder().id(id).name(name).enabled(enabled).draftDefinition(draft).deployedDefinition(deployed).build();
    }

    @Test
    void findetFlowsInDraftUndDeployed() {
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("sensor.zigbee_temperatur_buero_temperature", "Temperatur Büro"),
                entity("sensor.zigbee_temperatur_buero_humidity", "Temperatur Büro"),
                entity("sensor.zigbee_anderes_temperature", "Anderes")));
        when(flowRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                flow(4, "Feuer-Verdacht", true, null,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_temperatur_buero_temperature\"}}]}"),
                flow(9, "Entwurf", false,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_temperatur_buero_humidity\"}}]}", null),
                flow(2, "Unbeteiligt", true, null,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_anderes_temperature\"}}]}")));

        List<FlowReferenceResponse> refs = service.references("Temperatur Büro");

        assertThat(refs).containsExactly(
                new FlowReferenceResponse(4L, "Feuer-Verdacht", true),
                new FlowReferenceResponse(9L, "Entwurf", false));
    }

    @Test
    void teilstringTrefferIstBewusstEinTreffer() {
        // Falsch-positiv ist bei einer Warnung akzeptabel, falsch-negativ nicht.
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("sensor.zigbee_buero_temperature", "Büro")));
        when(flowRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                flow(1, "Anderer Sensor", true, null, "{\"entityId\":\"sensor.zigbee_buero_temperature_2\"}")));

        assertThat(service.references("Büro")).hasSize(1);
    }

    @Test
    void ohneEntitaetenKeineFlowSuche() {
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of());

        assertThat(service.references("Nie gesendet")).isEmpty();
        verify(flowRepository, never()).findAllByOrderByNameAsc();
    }
}
