package com.household.manager.service;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VentilationEntityReporterTest {

    private VentilationRecommendationService recommendationService;
    private EntityStateService entityStateService;
    private VentilationEntityReporter reporter;

    @BeforeEach
    void setUp() {
        recommendationService = Mockito.mock(VentilationRecommendationService.class);
        entityStateService = Mockito.mock(EntityStateService.class);
        reporter = new VentilationEntityReporter(recommendationService, entityStateService);
    }

    private VentilationAssessment assessment(Boolean recommended, List<VentilationRoom> rooms) {
        BigDecimal outdoor = recommended == null ? null : new BigDecimal("21.0");
        return new VentilationAssessment(recommended, outdoor, rooms, LocalDateTime.now());
    }

    @Test
    void meldetOnMitRaumAttributen() {
        when(recommendationService.assess()).thenReturn(assessment(true,
                List.of(new VentilationRoom("Schlafzimmer", new BigDecimal("26.0")))));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("binary_sensor.insight_ventilation");
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.attributes()).containsEntry("outdoorTemperature", new BigDecimal("21.0"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) update.attributes().get("rooms");
        assertThat(rooms).containsExactly(
                Map.of("name", "Schlafzimmer", "temperature", new BigDecimal("26.0")));
    }

    @Test
    void meldetOffOhneEmpfehlung() {
        when(recommendationService.assess()).thenReturn(assessment(false, List.of()));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("off");
    }

    @Test
    void meldetUnavailableOhneAussage() {
        when(recommendationService.assess()).thenReturn(assessment(null, List.of()));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void wirftNieBeiFehlerDerBewertung() {
        when(recommendationService.assess()).thenThrow(new IllegalStateException("kaputt"));

        reporter.report();
    }
}
