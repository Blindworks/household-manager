package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class EntityStateControllerTest {

    @Mock
    private EntityStateService entityStateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        EntityStateResponseMapper responseMapper = new EntityStateResponseMapper(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new EntityStateController(entityStateService, responseMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private EntityState sensor() {
        return EntityState.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state("21.5")
                .attributes("{\"unit\":\"°C\"}")
                .lastChanged(LocalDateTime.of(2026, 7, 8, 12, 0))
                .lastUpdated(LocalDateTime.of(2026, 7, 8, 12, 5))
                .build();
    }

    @Test
    void listsAllEntities() throws Exception {
        when(entityStateService.find(null, null)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value("sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(jsonPath("$[0].state").value("21.5"))
                .andExpect(jsonPath("$[0].attributes.unit").value("°C"))
                .andExpect(jsonPath("$[0].lastChanged").value("2026-07-08T12:00:00"));
    }

    @Test
    void filtersByDomainAndSource() throws Exception {
        when(entityStateService.find(EntityDomain.SENSOR, EntitySource.ZIGBEE)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities").param("domain", "SENSOR").param("source", "ZIGBEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].domain").value("SENSOR"));
    }

    @Test
    void returnsSingleEntityWithDotsInId() throws Exception {
        when(entityStateService.getByEntityId("sensor.zigbee_wohnzimmer_temperature"))
                .thenReturn(Optional.of(sensor()));

        mockMvc.perform(get("/v1/entities/sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendlyName").value("Wohnzimmer Temperatur"));
    }

    @Test
    void returns404ForUnknownEntity() throws Exception {
        when(entityStateService.getByEntityId("sensor.unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/entities/sensor.unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesEntity() throws Exception {
        when(entityStateService.deleteByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(true);

        mockMvc.perform(delete("/v1/entities/sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404ForUnknownEntity() throws Exception {
        when(entityStateService.deleteByEntityId("sensor.unknown")).thenReturn(false);

        mockMvc.perform(delete("/v1/entities/sensor.unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidDomainParamReturns400() throws Exception {
        mockMvc.perform(get("/v1/entities").param("domain", "FOO"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void displayNameFallsBackToFriendlyNameWhenNoCustomName() throws Exception {
        when(entityStateService.find(null, null)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customName").doesNotExist())
                .andExpect(jsonPath("$[0].displayName").value("Wohnzimmer Temperatur"));
    }

    @Test
    void displayNameUsesCustomNameWhenSet() throws Exception {
        EntityState withCustom = sensor();
        withCustom.setCustomName("Wohnzimmer");
        when(entityStateService.find(null, null)).thenReturn(List.of(withCustom));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customName").value("Wohnzimmer"))
                .andExpect(jsonPath("$[0].displayName").value("Wohnzimmer"));
    }
}
