package com.household.manager.controller;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.service.VentilationRecommendationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class InsightControllerTest {

    @Test
    void liefertDieBewertungDesServices() {
        VentilationRecommendationService service =
                Mockito.mock(VentilationRecommendationService.class);
        VentilationAssessment assessment =
                new VentilationAssessment(null, null, List.of(), LocalDateTime.now());
        when(service.assess()).thenReturn(assessment);

        InsightController controller = new InsightController(service);

        assertThat(controller.getVentilation()).isSameAs(assessment);
    }
}
