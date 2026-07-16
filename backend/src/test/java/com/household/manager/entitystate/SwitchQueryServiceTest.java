package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwitchQueryServiceTest {

    @Mock
    private EntityStateRepository entityStateRepository;
    @Mock
    private EntityUsageService entityUsageService;

    private SwitchQueryService service;

    @BeforeEach
    void setUp() {
        service = new SwitchQueryService(entityStateRepository, entityUsageService,
                new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper())));
    }

    private EntityState device(String ref, String name) {
        return EntityState.builder()
                .entityId("switch.kasa_" + ref)
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef(ref)
                .friendlyName(name)
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityUsage usage(String entityId, long count, LocalDateTime last) {
        return EntityUsage.builder().entityId(entityId).toggleCount(count).lastToggledAt(last).build();
    }

    private List<String> namesOf(List<SwitchResponse> switches) {
        return switches.stream().map(SwitchResponse::displayName).toList();
    }

    @Test
    void sortiert_meistgenutzte_zuerst() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Selten"), device("b", "Oft")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_a", usage("switch.kasa_a", 1, LocalDateTime.of(2026, 7, 15, 10, 0)),
                "switch.kasa_b", usage("switch.kasa_b", 9, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Oft", "Selten");
    }

    @Test
    void trennt_gleichstand_ueber_den_letzten_schaltzeitpunkt() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Aelter"), device("b", "Neuer")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_a", usage("switch.kasa_a", 3, LocalDateTime.of(2026, 7, 10, 10, 0)),
                "switch.kasa_b", usage("switch.kasa_b", 3, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Neuer", "Aelter");
    }

    @Test
    void sortiert_nie_genutzte_alphabetisch_ans_ende() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Zebra"), device("b", "Ampel"), device("c", "Genutzt")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_c", usage("switch.kasa_c", 2, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Genutzt", "Ampel", "Zebra");
    }

    @Test
    void begrenzt_die_liste_auf_das_limit() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Eins"), device("b", "Zwei"), device("c", "Drei")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(2)).hasSize(2);
    }

    @Test
    void ein_limit_groesser_als_die_liste_schneidet_nichts_ab() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Eins")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(10)).hasSize(1);
    }

    @Test
    void filtert_nicht_schaltbare_entitaeten_heraus() {
        EntityState zigbeeSwitch = EntityState.builder()
                .entityId("switch.zigbee_x")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.ZIGBEE)
                .sourceRef("x")
                .friendlyName("Zigbee-Schalter")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Steckdose"), zigbeeSwitch));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Steckdose");
    }
}
