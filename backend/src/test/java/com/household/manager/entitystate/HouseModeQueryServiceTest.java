package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseModeQueryServiceTest {

    @Mock
    private EntityStateRepository entityStateRepository;

    private HouseModeQueryService service;

    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        service = new HouseModeQueryService(entityStateRepository, entityMapper,
                new ModeResponseMapper(entityMapper));
    }

    private EntityState manualBoolean(String ref, String name, String state, String attributes) {
        return EntityState.builder()
                .entityId("input_boolean.manual_" + ref)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }

    @Test
    void liefert_nur_marker_entities_in_katalog_reihenfolge() {
        // Repository liefert nach entityId sortiert.
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(any(), any())).thenReturn(List.of(
                manualBoolean("ausschalten", "Ausschalten", "off", "{\"icon\":\"power_settings_new\",\"mode\":true}"),
                manualBoolean("nachtmodus", "Nachtmodus", "on", "{\"icon\":\"nights_stay\",\"mode\":true}"),
                manualBoolean("urlaub", "Urlaub", "off", "{\"mode\":true}"),
                manualBoolean("gewoehnlich", "Gewöhnlicher Helfer", "on", "{\"icon\":\"toggle_on\"}")
        ));

        List<ModeResponse> modes = service.listModes();

        // Katalog-Modi zuerst in Katalog-Reihenfolge, unbekannte Marker-Entities dahinter;
        // der Helfer ohne Marker fehlt.
        assertThat(modes).extracting(ModeResponse::entityId).containsExactly(
                "input_boolean.manual_nachtmodus",
                "input_boolean.manual_ausschalten",
                "input_boolean.manual_urlaub");
    }

    @Test
    void bildet_name_icon_und_zustand_ab_mit_icon_fallback() {
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(any(), any())).thenReturn(List.of(
                manualBoolean("nachtmodus", "Nachtmodus", "on", "{\"icon\":\"nights_stay\",\"mode\":true}"),
                manualBoolean("urlaub", "Urlaub", "off", "{\"mode\":true}")
        ));

        List<ModeResponse> modes = service.listModes();

        assertThat(modes.get(0).displayName()).isEqualTo("Nachtmodus");
        assertThat(modes.get(0).icon()).isEqualTo("nights_stay");
        assertThat(modes.get(0).state()).isEqualTo("on");
        assertThat(modes.get(1).icon()).isEqualTo("flag");
    }
}
