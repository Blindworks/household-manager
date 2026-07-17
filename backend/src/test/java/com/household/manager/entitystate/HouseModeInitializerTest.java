package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseModeInitializerTest {

    @Mock
    private EntityStateService entityStateService;

    private HouseModeInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new HouseModeInitializer(entityStateService,
                new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState modeEntity(String entityId, String state, String attributes) {
        return EntityState.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(entityId.substring("input_boolean.manual_".length()))
                .friendlyName("Bestand")
                .state(state)
                .attributes(attributes)
                .build();
    }

    @Test
    void legt_fehlende_modi_mit_marker_und_icon_an() {
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());

        initializer.seedHouseModes();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(4)).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId).containsExactly(
                "input_boolean.manual_abwesend",
                "input_boolean.manual_toni_allein",
                "input_boolean.manual_nachtmodus",
                "input_boolean.manual_ausschalten");
        EntityStateUpdate first = captor.getAllValues().get(0);
        assertThat(first.friendlyName()).isEqualTo("Abwesend");
        assertThat(first.state()).isEqualTo("off");
        assertThat(first.attributes())
                .containsEntry("mode", true)
                .containsEntry("icon", "exit_to_app");
    }

    @Test
    void ergaenzt_nur_den_marker_bei_vorhandener_entity_ohne_marker() {
        when(entityStateService.getByEntityId(anyString()))
                .thenAnswer(invocation -> Optional.of(modeEntity(
                        invocation.getArgument(0), "on", "{\"mode\":true}")));
        when(entityStateService.getByEntityId("input_boolean.manual_nachtmodus"))
                .thenReturn(Optional.of(modeEntity(
                        "input_boolean.manual_nachtmodus", "on", "{\"icon\":\"bedtime\"}")));

        initializer.seedHouseModes();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("input_boolean.manual_nachtmodus");
        // Zustand, Name und vorhandene Attribute bleiben unangetastet:
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.friendlyName()).isEqualTo("Bestand");
        assertThat(update.attributes())
                .containsEntry("icon", "bedtime")
                .containsEntry("mode", true);
    }

    @Test
    void laesst_vollstaendig_markierte_modi_unangetastet() {
        when(entityStateService.getByEntityId(anyString()))
                .thenAnswer(invocation -> Optional.of(modeEntity(
                        invocation.getArgument(0), "off", "{\"icon\":\"pets\",\"mode\":true}")));

        initializer.seedHouseModes();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void ein_fehler_bei_einem_modus_stoppt_die_uebrigen_nicht() {
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());
        when(entityStateService.getByEntityId("input_boolean.manual_abwesend"))
                .thenThrow(new RuntimeException("DB nicht erreichbar"));

        initializer.seedHouseModes();

        verify(entityStateService, times(3)).reportState(any());
    }
}
