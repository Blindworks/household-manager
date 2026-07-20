package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SwitchControllerTest {

    @Mock
    private SwitchQueryService switchQueryService;
    @Mock
    private SwitchCommandService switchCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SwitchController(switchQueryService, switchCommandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private SwitchResponse response(String entityId, String name, String state) {
        return SwitchResponse.builder()
                .entityId(entityId)
                .domain("SWITCH")
                .source("KASA")
                .displayName(name)
                .state(state)
                .available(true)
                .icon("toggle_on")
                .toggleCount(3)
                .build();
    }

    @Test
    void liefert_die_schalterliste() throws Exception {
        when(switchQueryService.listSwitches(isNull(), eq(false)))
                .thenReturn(List.of(response("switch.kasa_abc", "Stehlampe", "on")));

        mockMvc.perform(get("/v1/switches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value("switch.kasa_abc"))
                .andExpect(jsonPath("$[0].displayName").value("Stehlampe"))
                .andExpect(jsonPath("$[0].state").value("on"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].toggleCount").value(3));
    }

    @Test
    void reicht_das_limit_an_den_service_durch() throws Exception {
        when(switchQueryService.listSwitches(4, false)).thenReturn(List.of());

        mockMvc.perform(get("/v1/switches").param("limit", "4"))
                .andExpect(status().isOk());
    }

    @Test
    void view_tile_aktiviert_die_kachel_sicht() throws Exception {
        when(switchQueryService.listSwitches(4, true)).thenReturn(List.of());

        mockMvc.perform(get("/v1/switches").param("limit", "4").param("view", "tile"))
                .andExpect(status().isOk());
    }

    @Test
    void schaltet_einen_schalter_um() throws Exception {
        when(switchCommandService.toggle("switch.kasa_abc"))
                .thenReturn(response("switch.kasa_abc", "Stehlampe", "off"));

        mockMvc.perform(post("/v1/switches/switch.kasa_abc/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("off"));
    }

    @Test
    void unbekannter_schalter_liefert_404() throws Exception {
        when(switchCommandService.toggle("switch.kasa_weg"))
                .thenThrow(new ResourceNotFoundException("Entity not found: switch.kasa_weg"));

        mockMvc.perform(post("/v1/switches/switch.kasa_weg/toggle"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nicht_schaltbare_entitaet_liefert_400() throws Exception {
        when(switchCommandService.toggle("sensor.zigbee_bad_temperature"))
                .thenThrow(new IllegalArgumentException("Entity is not switchable"));

        mockMvc.perform(post("/v1/switches/sensor.zigbee_bad_temperature/toggle"))
                .andExpect(status().isBadRequest());
    }
}
