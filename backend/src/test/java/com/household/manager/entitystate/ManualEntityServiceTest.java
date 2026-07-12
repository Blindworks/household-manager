package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualEntityServiceTest {

    @Mock
    private EntityStateService entityStateService;

    private ManualEntityService service;

    @BeforeEach
    void setUp() {
        service = new ManualEntityService(entityStateService, new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState manual(String entityId, String state, String attributesJson) {
        return EntityState.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state(state)
                .attributes(attributesJson)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityStateUpdate capturedUpdate() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        return captor.getValue();
    }

    @Test
    void createDerivesStableIdAndDefaultsToOff() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(manual(id, "off", null)));

        EntityState result = service.create("Nachtmodus", null, null);

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.entityId()).isEqualTo(id);
        assertThat(update.domain()).isEqualTo(EntityDomain.INPUT_BOOLEAN);
        assertThat(update.source()).isEqualTo(EntitySource.MANUAL);
        assertThat(update.state()).isEqualTo("off");
        assertThat(update.friendlyName()).isEqualTo("Nachtmodus");
        assertThat(result.getEntityId()).isEqualTo(id);
    }

    @Test
    void createStoresIconAndInitialOnState() {
        String id = "input_boolean.manual_haus_abgeschlossen";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(manual(id, "on", "{\"icon\":\"🔒\"}")));

        service.create("Haus abgeschlossen", "on", "🔒");

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.attributes()).containsEntry("icon", "🔒");
    }

    @Test
    void createRejectsDuplicateId() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "off", null)));

        assertThatThrownBy(() -> service.create("Nachtmodus", null, null))
                .isInstanceOf(DuplicateEntityException.class);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void createRejectsUnsupportedState() {
        when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("Nachtmodus", "bright", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setStateNormalizesTruthyInput() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "off", null)));

        service.setState(id, "true");

        assertThat(capturedUpdate().state()).isEqualTo("on");
    }

    @Test
    void setStateFailsForUnknownEntity() {
        when(entityStateService.getByEntityId("input_boolean.manual_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setState("input_boolean.manual_x", "on"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setStateRejectsNonManualEntity() {
        EntityState kasa = EntityState.builder()
                .entityId("switch.kasa_abc").domain(EntityDomain.SWITCH).source(EntitySource.KASA)
                .sourceRef("abc").friendlyName("Steckdose").state("on")
                .lastChanged(LocalDateTime.now()).lastUpdated(LocalDateTime.now()).build();
        when(entityStateService.getByEntityId("switch.kasa_abc")).thenReturn(Optional.of(kasa));

        assertThatThrownBy(() -> service.setState("switch.kasa_abc", "off"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void toggleFlipsOnToOff() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "on", null)));

        service.toggle(id);

        assertThat(capturedUpdate().state()).isEqualTo("off");
    }

    @Test
    void toggleTreatsUnknownAsOn() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "unknown", null)));

        service.toggle(id);

        assertThat(capturedUpdate().state()).isEqualTo("on");
    }

    @Test
    void renameKeepsIdAndStateButUpdatesName() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "on", "{\"icon\":\"🌙\"}")));

        service.rename(id, "Schlafmodus", null);

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.entityId()).isEqualTo(id);
        assertThat(update.friendlyName()).isEqualTo("Schlafmodus");
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.attributes()).containsEntry("icon", "🌙");
    }

    @Test
    void deleteOnlyRemovesManualEntities() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id)).thenReturn(Optional.of(manual(id, "off", null)));

        service.delete(id);

        verify(entityStateService).deleteByEntityId(id);
    }
}
