package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityUsageServiceTest {

    @Mock
    private EntityUsageRepository repository;

    private EntityUsageService service;

    @BeforeEach
    void setUp() {
        service = new EntityUsageService(repository);
    }

    private EntityUsage saved() {
        ArgumentCaptor<EntityUsage> captor = ArgumentCaptor.forClass(EntityUsage.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void legt_den_zaehler_beim_ersten_schalten_an() {
        when(repository.findByEntityId("switch.kasa_abc")).thenReturn(Optional.empty());
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordToggle("switch.kasa_abc");

        EntityUsage usage = saved();
        assertThat(usage.getEntityId()).isEqualTo("switch.kasa_abc");
        assertThat(usage.getToggleCount()).isEqualTo(1);
        assertThat(usage.getLastToggledAt()).isNotNull();
    }

    @Test
    void erhoeht_einen_bestehenden_zaehler() {
        EntityUsage existing = EntityUsage.builder()
                .entityId("switch.kasa_abc")
                .toggleCount(4)
                .lastToggledAt(LocalDateTime.of(2026, 7, 1, 8, 0))
                .build();
        when(repository.findByEntityId("switch.kasa_abc")).thenReturn(Optional.of(existing));
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordToggle("switch.kasa_abc");

        EntityUsage usage = saved();
        assertThat(usage.getToggleCount()).isEqualTo(5);
        assertThat(usage.getLastToggledAt()).isAfter(LocalDateTime.of(2026, 7, 1, 8, 0));
    }

    @Test
    void gibt_die_aktualisierte_nutzung_zurueck() {
        when(repository.findByEntityId("input_boolean.manual_nachtmodus")).thenReturn(Optional.empty());
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        EntityUsage result = service.recordToggle("input_boolean.manual_nachtmodus");

        assertThat(result.getToggleCount()).isEqualTo(1);
    }

    @Test
    void indiziert_die_nutzung_nach_entity_id() {
        EntityUsage usage = EntityUsage.builder().entityId("switch.kasa_abc").toggleCount(2).build();
        when(repository.findByEntityIdIn(List.of("switch.kasa_abc"))).thenReturn(List.of(usage));

        Map<String, EntityUsage> result = service.usageFor(List.of("switch.kasa_abc"));

        assertThat(result).containsOnlyKeys("switch.kasa_abc");
        assertThat(result.get("switch.kasa_abc").getToggleCount()).isEqualTo(2);
    }

    @Test
    void fragt_bei_leerer_id_liste_nicht_die_datenbank() {
        Map<String, EntityUsage> result = service.usageFor(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }
}
