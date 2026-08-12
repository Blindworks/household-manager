package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import com.household.manager.service.VentilationRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InsightControllerTest {

    @Mock
    private VentilationRecommendationService ventilationRecommendationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InsightController(ventilationRecommendationService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void liefertDieLueftungsempfehlung() throws Exception {
        VentilationAssessment assessment = new VentilationAssessment(
                true,
                new BigDecimal("21.0"),
                List.of(new VentilationRoom("Schlafzimmer", new BigDecimal("19.5"))),
                LocalDateTime.of(2026, 8, 12, 12, 0));
        when(ventilationRecommendationService.assess()).thenReturn(assessment);

        mockMvc.perform(get("/v1/insights/ventilation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").value(true))
                .andExpect(jsonPath("$.outdoorTemperature").value(21.0))
                .andExpect(jsonPath("$.rooms[0].name").value("Schlafzimmer"));
    }

    @Test
    void liefertKeineAussageAlsNull() throws Exception {
        VentilationAssessment assessment =
                new VentilationAssessment(null, null, List.of(), LocalDateTime.of(2026, 8, 12, 12, 0));
        when(ventilationRecommendationService.assess()).thenReturn(assessment);

        mockMvc.perform(get("/v1/insights/ventilation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended").value(nullValue()));
    }
}
