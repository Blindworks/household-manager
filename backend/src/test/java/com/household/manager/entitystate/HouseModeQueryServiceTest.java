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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseModeQueryServiceTest {

    @Mock
    private EntityStateRepository entityStateRepository;

    @Mock
    private EntityTileVisibilityService tileVisibilityService;

    private HouseModeQueryService service;

    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        when(tileVisibilityService.tileRules(DashboardTiles.MODES)).thenReturn(Map.of());
        service = new HouseModeQueryService(entityStateRepository, entityMapper,
                new ModeResponseMapper(entityMapper), tileVisibilityService);
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

    /**
     * Die Leiste ist seit 2026-09-15 admin-konfigurierbar ueber die Kachel-Sichtbarkeit
     * "modes": ALWAYS holt einen gewoehnlichen Helfer hinein, NEVER nimmt einen Modus heraus,
     * WHEN_ON zeigt ihn nur solange er an ist. Hinzugeholte Helfer stehen hinter dem Katalog.
     */
    @Test
    void wendet_die_sichtbarkeitsregeln_der_modus_leiste_an() {
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(any(), any())).thenReturn(List.of(
                manualBoolean("abwesend", "Abwesend", "off", "{\"icon\":\"exit_to_app\",\"mode\":true}"),
                manualBoolean("kamin", "Kamin", "off", "{\"icon\":\"fireplace\"}"),
                manualBoolean("nachtmodus", "Nachtmodus", "on", "{\"icon\":\"nights_stay\",\"mode\":true}"),
                manualBoolean("party", "Party", "off", "{}"),
                manualBoolean("urlaub", "Urlaub", "on", "{}")
        ));
        when(tileVisibilityService.tileRules(DashboardTiles.MODES)).thenReturn(Map.of(
                "input_boolean.manual_kamin", TileVisibility.ALWAYS,
                "input_boolean.manual_nachtmodus", TileVisibility.NEVER,
                "input_boolean.manual_party", TileVisibility.WHEN_ON,
                "input_boolean.manual_urlaub", TileVisibility.WHEN_ON
        ));

        List<ModeResponse> modes = service.listModes();

        // Abwesend (Katalog, AUTO) bleibt; Nachtmodus (NEVER) fehlt; Kamin (ALWAYS) kommt dazu;
        // Party (WHEN_ON, aus) fehlt; Urlaub (WHEN_ON, an) kommt dazu.
        assertThat(modes).extracting(ModeResponse::entityId).containsExactly(
                "input_boolean.manual_abwesend",
                "input_boolean.manual_kamin",
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
