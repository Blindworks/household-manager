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
import java.util.List;
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

    private EntityState entity(String entityId, EntityDomain domain, String state, String attributesJson) {
        return EntityState.builder()
                .entityId(entityId)
                .domain(domain)
                .source(EntitySource.MANUAL)
                .sourceRef("ref")
                .friendlyName("Name")
                .state(state)
                .attributes(attributesJson)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private ManualEntityDefinition.ManualEntityDefinitionBuilder def(EntityDomain domain, String name) {
        return ManualEntityDefinition.builder().domain(domain).name(name);
    }

    private EntityStateUpdate capturedUpdate() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        return captor.getValue();
    }

    // --- Boolean ---------------------------------------------------------------------

    @Test
    void createBooleanDerivesStableIdAndDefaultsToOff() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(entity(id, EntityDomain.INPUT_BOOLEAN, "off", null)));

        EntityState result = service.create(def(EntityDomain.INPUT_BOOLEAN, "Nachtmodus").build());

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.entityId()).isEqualTo(id);
        assertThat(update.domain()).isEqualTo(EntityDomain.INPUT_BOOLEAN);
        assertThat(update.source()).isEqualTo(EntitySource.MANUAL);
        assertThat(update.state()).isEqualTo("off");
        assertThat(result.getEntityId()).isEqualTo(id);
    }

    @Test
    void createDefaultsToBooleanWhenTypeMissing() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(entity(id, EntityDomain.INPUT_BOOLEAN, "off", null)));

        service.create(ManualEntityDefinition.builder().name("Nachtmodus").build());

        assertThat(capturedUpdate().domain()).isEqualTo(EntityDomain.INPUT_BOOLEAN);
    }

    @Test
    void createRejectsDuplicateId() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_BOOLEAN, "off", null)));

        assertThatThrownBy(() -> service.create(def(EntityDomain.INPUT_BOOLEAN, "Nachtmodus").build()))
                .isInstanceOf(DuplicateEntityException.class);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void toggleFlipsOnToOff() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_BOOLEAN, "on", null)));

        service.toggle(id);

        assertThat(capturedUpdate().state()).isEqualTo("off");
    }

    @Test
    void toggleRejectsNonBooleanEntity() {
        String id = "input_number.manual_temperatur";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_NUMBER, "21", null)));

        assertThatThrownBy(() -> service.toggle(id)).isInstanceOf(IllegalArgumentException.class);
        verify(entityStateService, never()).reportState(any());
    }

    // --- Number ----------------------------------------------------------------------

    @Test
    void createNumberStoresBoundsAndDefaultsToMin() {
        String id = "input_number.manual_zieltemperatur";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(entity(id, EntityDomain.INPUT_NUMBER, "16", "{\"min\":16.0}")));

        service.create(def(EntityDomain.INPUT_NUMBER, "Zieltemperatur")
                .min(16d).max(28d).step(0.5d).unit("°C").build());

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.state()).isEqualTo("16");
        assertThat(update.attributes()).containsEntry("min", 16d).containsEntry("max", 28d)
                .containsEntry("step", 0.5d).containsEntry("unit", "°C");
    }

    @Test
    void createNumberRejectsMinGreaterThanMax() {
        when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(def(EntityDomain.INPUT_NUMBER, "X").min(10d).max(5d).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setNumberStateRejectsOutOfRange() {
        String id = "input_number.manual_x";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_NUMBER, "16", "{\"min\":16.0,\"max\":28.0}")));

        assertThatThrownBy(() -> service.setState(id, "40")).isInstanceOf(IllegalArgumentException.class);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void setNumberStateRejectsNonNumeric() {
        String id = "input_number.manual_x";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_NUMBER, "16", null)));

        assertThatThrownBy(() -> service.setState(id, "warm")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setNumberStateAcceptsInRangeAndFormatsIntegral() {
        String id = "input_number.manual_x";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_NUMBER, "16", "{\"min\":16.0,\"max\":28.0}")));

        service.setState(id, "22.0");

        assertThat(capturedUpdate().state()).isEqualTo("22");
    }

    // --- Text ------------------------------------------------------------------------

    @Test
    void createTextDefaultsToEmptyAndAcceptsFreeText() {
        String id = "input_text.manual_notiz";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(), Optional.of(entity(id, EntityDomain.INPUT_TEXT, "", null)));

        service.create(def(EntityDomain.INPUT_TEXT, "Notiz").build());

        assertThat(capturedUpdate().state()).isEmpty();
    }

    @Test
    void setTextStateStoresTrimmedValue() {
        String id = "input_text.manual_notiz";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_TEXT, "", null)));

        service.setState(id, "  Paket kommt Freitag  ");

        assertThat(capturedUpdate().state()).isEqualTo("Paket kommt Freitag");
    }

    // --- Select ----------------------------------------------------------------------

    @Test
    void createSelectStoresOptionsAndDefaultsToFirst() {
        String id = "input_select.manual_urlaubsstatus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.empty(),
                        Optional.of(entity(id, EntityDomain.INPUT_SELECT, "Zuhause", "{\"options\":[\"Zuhause\",\"Urlaub\"]}")));

        service.create(def(EntityDomain.INPUT_SELECT, "Urlaubsstatus")
                .options(List.of("Zuhause", " Urlaub ", "Zuhause", "")).build());

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.state()).isEqualTo("Zuhause");
        assertThat(update.attributes()).containsEntry("options", List.of("Zuhause", "Urlaub"));
    }

    @Test
    void createSelectRejectsMissingOptions() {
        when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(def(EntityDomain.INPUT_SELECT, "X").options(List.of()).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setSelectStateRejectsValueOutsideOptions() {
        String id = "input_select.manual_x";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_SELECT, "Zuhause", "{\"options\":[\"Zuhause\",\"Urlaub\"]}")));

        assertThatThrownBy(() -> service.setState(id, "Mond")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setSelectStateAcceptsKnownOption() {
        String id = "input_select.manual_x";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_SELECT, "Zuhause", "{\"options\":[\"Zuhause\",\"Urlaub\"]}")));

        service.setState(id, "Urlaub");

        assertThat(capturedUpdate().state()).isEqualTo("Urlaub");
    }

    // --- Common ----------------------------------------------------------------------

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
    void renameKeepsIdStateAndConfigButUpdatesName() {
        String id = "input_select.manual_urlaubsstatus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_SELECT, "Urlaub",
                        "{\"icon\":\"🏖️\",\"options\":[\"Zuhause\",\"Urlaub\"]}")));

        service.rename(id, "Status", "🏠");

        EntityStateUpdate update = capturedUpdate();
        assertThat(update.entityId()).isEqualTo(id);
        assertThat(update.friendlyName()).isEqualTo("Status");
        assertThat(update.state()).isEqualTo("Urlaub");
        assertThat(update.attributes()).containsEntry("icon", "🏠")
                .containsEntry("options", List.of("Zuhause", "Urlaub"));
    }

    @Test
    void deleteOnlyRemovesManualEntities() {
        String id = "input_boolean.manual_nachtmodus";
        when(entityStateService.getByEntityId(id))
                .thenReturn(Optional.of(entity(id, EntityDomain.INPUT_BOOLEAN, "off", null)));

        service.delete(id);

        verify(entityStateService).deleteByEntityId(id);
    }
}
