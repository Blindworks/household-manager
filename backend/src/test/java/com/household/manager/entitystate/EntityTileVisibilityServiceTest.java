package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityTileVisibilityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityTileVisibilityServiceTest {

    @Mock
    private EntityTileVisibilityRepository repository;

    @InjectMocks
    private EntityTileVisibilityService service;

    private EntityTileVisibility rule(String entityId, String tileKey, TileVisibility visibility) {
        return EntityTileVisibility.builder()
                .entityId(entityId)
                .tileKey(tileKey)
                .visibility(visibility)
                .updatedAt(LocalDateTime.of(2026, 7, 20, 10, 0))
                .build();
    }

    @Test
    void legt_neue_regel_an() {
        when(repository.findByEntityIdAndTileKey("switch.kasa_wm", "switches"))
                .thenReturn(Optional.empty());

        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.WHEN_ON);

        ArgumentCaptor<EntityTileVisibility> captor = ArgumentCaptor.forClass(EntityTileVisibility.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEntityId()).isEqualTo("switch.kasa_wm");
        assertThat(captor.getValue().getTileKey()).isEqualTo("switches");
        assertThat(captor.getValue().getVisibility()).isEqualTo(TileVisibility.WHEN_ON);
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void aktualisiert_bestehende_regel() {
        EntityTileVisibility existing = rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON);
        when(repository.findByEntityIdAndTileKey("switch.kasa_wm", "switches"))
                .thenReturn(Optional.of(existing));

        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.NEVER);

        ArgumentCaptor<EntityTileVisibility> captor = ArgumentCaptor.forClass(EntityTileVisibility.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo(TileVisibility.NEVER);
    }

    @Test
    void auto_loescht_die_regel_statt_sie_zu_speichern() {
        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.AUTO);

        verify(repository).deleteByEntityIdAndTileKey("switch.kasa_wm", "switches");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void liefert_die_regeln_einer_kachel_als_map() {
        when(repository.findByTileKey("switches")).thenReturn(List.of(
                rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON),
                rule("switch.kasa_stehlampe", "switches", TileVisibility.ALWAYS)
        ));

        Map<String, TileVisibility> rules = service.tileRules("switches");

        assertThat(rules).containsExactlyInAnyOrderEntriesOf(Map.of(
                "switch.kasa_wm", TileVisibility.WHEN_ON,
                "switch.kasa_stehlampe", TileVisibility.ALWAYS
        ));
    }

    @Test
    void gruppiert_regeln_je_entitaet_fuer_die_api_antwort() {
        when(repository.findByEntityIdIn(List.of("switch.kasa_wm"))).thenReturn(List.of(
                rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON)
        ));

        Map<String, Map<String, String>> byEntity = service.visibilityByEntity(List.of("switch.kasa_wm"));

        assertThat(byEntity).containsExactlyEntriesOf(
                Map.of("switch.kasa_wm", Map.of("switches", "WHEN_ON")));
    }

    @Test
    void leere_entity_liste_fragt_das_repository_nicht_ab() {
        assertThat(service.visibilityByEntity(List.of())).isEmpty();
        verify(repository, never()).findByEntityIdIn(org.mockito.ArgumentMatchers.anyCollection());
    }
}
