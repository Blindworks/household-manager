package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchableEntitiesTest {

    private EntityState entity(EntityDomain domain, EntitySource source) {
        return EntityState.builder()
                .entityId("x.y")
                .domain(domain)
                .source(source)
                .sourceRef("ref")
                .friendlyName("Name")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    @Test
    void smart_device_schalter_sind_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.KASA))).isTrue();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.TAPO))).isTrue();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.MEROSS))).isTrue();
    }

    @Test
    void manuelle_boolean_helfer_sind_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL))).isTrue();
    }

    @Test
    void schalter_ohne_geraete_quelle_sind_nicht_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.ZIGBEE))).isFalse();
    }

    @Test
    void sensoren_und_andere_helfer_sind_nicht_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SENSOR, EntitySource.ZIGBEE))).isFalse();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_NUMBER, EntitySource.MANUAL))).isFalse();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_TEXT, EntitySource.MANUAL))).isFalse();
    }

    @Test
    void die_vorfilter_domains_decken_beide_schaltbaren_faelle_ab() {
        assertThat(SwitchableEntities.SWITCHABLE_DOMAINS)
                .containsExactlyInAnyOrder(EntityDomain.SWITCH, EntityDomain.INPUT_BOOLEAN);
    }
}
