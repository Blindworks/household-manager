package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.exception.ResourceNotFoundException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ManualEntityControllerTest {

    private static final String ID = "input_boolean.manual_nachtmodus";

    @Mock
    private ManualEntityService manualEntityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        EntityStateResponseMapper responseMapper = new EntityStateResponseMapper(objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new ManualEntityController(manualEntityService, responseMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private EntityState entity(String state) {
        return EntityState.builder()
                .entityId(ID)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state(state)
                .attributes("{\"icon\":\"🌙\"}")
                .lastChanged(LocalDateTime.of(2026, 7, 12, 20, 0))
                .lastUpdated(LocalDateTime.of(2026, 7, 12, 20, 0))
                .build();
    }

    @Test
    void createsManualEntity() throws Exception {
        when(manualEntityService.create(eq("Nachtmodus"), eq("off"), eq("🌙"))).thenReturn(entity("off"));

        mockMvc.perform(post("/v1/entities/manual")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Nachtmodus\",\"state\":\"off\",\"icon\":\"🌙\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entityId").value(ID))
                .andExpect(jsonPath("$.domain").value("INPUT_BOOLEAN"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.attributes.icon").value("🌙"));
    }

    @Test
    void rejectsBlankName() throws Exception {
        mockMvc.perform(post("/v1/entities/manual")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verify(manualEntityService, never()).create(any(), any(), any());
    }

    @Test
    void returnsConflictForDuplicate() throws Exception {
        when(manualEntityService.create(any(), any(), any()))
                .thenThrow(new DuplicateEntityException("Entity already exists: " + ID));

        mockMvc.perform(post("/v1/entities/manual")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Nachtmodus\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void setsState() throws Exception {
        when(manualEntityService.setState(ID, "on")).thenReturn(entity("on"));

        mockMvc.perform(put("/v1/entities/manual/{id}/state", ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"state\":\"on\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("on"));
    }

    @Test
    void togglesState() throws Exception {
        when(manualEntityService.toggle(ID)).thenReturn(entity("on"));

        mockMvc.perform(post("/v1/entities/manual/{id}/toggle", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("on"));
    }

    @Test
    void renamesEntity() throws Exception {
        when(manualEntityService.rename(eq(ID), eq("Schlafmodus"), any())).thenReturn(entity("off"));

        mockMvc.perform(put("/v1/entities/manual/{id}", ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Schlafmodus\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deletesEntity() throws Exception {
        mockMvc.perform(delete("/v1/entities/manual/{id}", ID))
                .andExpect(status().isNoContent());
        verify(manualEntityService).delete(ID);
    }

    @Test
    void setStateReturns404ForUnknownEntity() throws Exception {
        when(manualEntityService.setState(eq(ID), any()))
                .thenThrow(new ResourceNotFoundException("Manual entity not found: " + ID));

        mockMvc.perform(put("/v1/entities/manual/{id}/state", ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"state\":\"on\"}"))
                .andExpect(status().isNotFound());
    }
}
