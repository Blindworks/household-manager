package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerConsumerQueryServiceTest {

    private static final String POWER_ATTRIBUTES = "{\"unit\":\"W\",\"deviceClass\":\"power\"}";
    private static final String TEMPERATURE_ATTRIBUTES = "{\"unit\":\"C\",\"deviceClass\":\"temperature\"}";

    @Mock
    private EntityStateRepository entityStateRepository;

    private PowerConsumerQueryService service;

    @BeforeEach
    void setUp() {
        service = new PowerConsumerQueryService(
                entityStateRepository, new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState sensor(EntitySource source, String ref, String name, String state, String attributes) {
        return EntityState.builder()
                .entityId("sensor." + source.name().toLowerCase() + "_" + ref + "_power")
                .domain(EntityDomain.SENSOR)
                .source(source)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private List<String> namesOf(List<PowerConsumerResponse> consumers) {
        return consumers.stream().map(PowerConsumerResponse::displayName).toList();
    }

    @Test
    void liefert_nur_power_sensoren() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "1200", POWER_ATTRIBUTES),
                sensor(EntitySource.ZIGBEE, "wz", "Wohnzimmer Temperatur", "21.5", TEMPERATURE_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine Leistung");
    }

    @Test
    void schliesst_haus_bilanz_quellen_aus() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "1200", POWER_ATTRIBUTES),
                sensor(EntitySource.TASMOTA, "main", "Hausverbrauch", "3400", POWER_ATTRIBUTES),
                sensor(EntitySource.ANKER_SOLIX, "pv_power", "Solarleistung", "800", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine Leistung");
    }

    @Test
    void schliesst_erzeuger_aus() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "1200", POWER_ATTRIBUTES),
                sensor(EntitySource.SHELLY, "bkw", "Balkonkraftwerk-Alt Leistung", "450", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine Leistung");
    }

    @Test
    void sortiert_absteigend_nach_leistung() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Klein", "5.5", POWER_ATTRIBUTES),
                sensor(EntitySource.TAPO, "b", "Gross", "1450", POWER_ATTRIBUTES),
                sensor(EntitySource.MEROSS, "c", "Mittel", "230", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Gross", "Mittel", "Klein");
    }

    @Test
    void nicht_numerische_states_gelten_als_unavailable_und_stehen_hinten() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Offline", "unavailable", POWER_ATTRIBUTES),
                sensor(EntitySource.TAPO, "b", "Aktiv", "42", POWER_ATTRIBUTES)));

        List<PowerConsumerResponse> consumers = service.listConsumers(null);

        assertThat(namesOf(consumers)).containsExactly("Aktiv", "Offline");
        assertThat(consumers.get(1).unavailable()).isTrue();
        assertThat(consumers.get(1).powerWatts()).isNull();
        assertThat(consumers.get(0).powerWatts()).isEqualByComparingTo(new BigDecimal("42"));
    }

    @Test
    void limit_kappt_die_liste_nach_der_sortierung() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Klein", "5", POWER_ATTRIBUTES),
                sensor(EntitySource.TAPO, "b", "Gross", "1450", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(1))).containsExactly("Gross");
    }

    @Test
    void custom_name_gewinnt_ueber_friendly_name() {
        EntityState entity = sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "10", POWER_ATTRIBUTES);
        entity.setCustomName("Waschmaschine");
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR))
                .thenReturn(List.of(entity));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine");
    }
}
