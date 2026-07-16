package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchResponseMapperTest {

    private SwitchResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState.EntityStateBuilder entity() {
        return EntityState.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Stehlampe")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now());
    }

    @Test
    void bildet_einen_schalter_mit_nutzung_ab() {
        EntityUsage usage = EntityUsage.builder()
                .entityId("switch.kasa_abc")
                .toggleCount(7)
                .lastToggledAt(LocalDateTime.of(2026, 7, 15, 20, 0))
                .build();

        SwitchResponse response = mapper.toResponse(entity().build(), usage);

        assertThat(response.entityId()).isEqualTo("switch.kasa_abc");
        assertThat(response.domain()).isEqualTo("SWITCH");
        assertThat(response.source()).isEqualTo("KASA");
        assertThat(response.displayName()).isEqualTo("Stehlampe");
        assertThat(response.state()).isEqualTo("on");
        assertThat(response.available()).isTrue();
        assertThat(response.toggleCount()).isEqualTo(7);
        assertThat(response.lastToggledAt()).isEqualTo(LocalDateTime.of(2026, 7, 15, 20, 0));
    }

    @Test
    void ohne_nutzung_ist_der_zaehler_null() {
        SwitchResponse response = mapper.toResponse(entity().build(), null);

        assertThat(response.toggleCount()).isZero();
        assertThat(response.lastToggledAt()).isNull();
    }

    @Test
    void der_kurzname_gewinnt_gegen_den_integrationsnamen() {
        SwitchResponse response = mapper.toResponse(entity().customName("Leselampe").build(), null);

        assertThat(response.displayName()).isEqualTo("Leselampe");
    }

    @Test
    void offline_geraete_sind_nicht_verfuegbar() {
        SwitchResponse response = mapper.toResponse(entity().state("unavailable").build(), null);

        assertThat(response.available()).isFalse();
    }

    @Test
    void nutzt_das_icon_aus_den_attributen() {
        EntityState manual = EntityState.builder()
                .entityId("input_boolean.manual_nachtmodus")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes("{\"icon\":\"bedtime\"}")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();

        SwitchResponse response = mapper.toResponse(manual, null);

        assertThat(response.icon()).isEqualTo("bedtime");
    }

    @Test
    void faellt_ohne_icon_attribut_auf_den_standard_zurueck() {
        SwitchResponse response = mapper.toResponse(entity().build(), null);

        assertThat(response.icon()).isEqualTo("toggle_on");
    }
}
