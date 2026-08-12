package com.household.manager.controller;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.service.VentilationRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serverseitig berechnete Hinweise für den Intelligence Hub.
 * Basis-URL: /api/v1/insights — lesbar für alle Rollen (generische GET-Regel,
 * auch das KIOSK-Wandtablet).
 */
@RestController
@RequestMapping("/v1/insights")
@RequiredArgsConstructor
public class InsightController {

    private final VentilationRecommendationService ventilationRecommendationService;

    @GetMapping("/ventilation")
    public VentilationAssessment getVentilation() {
        return ventilationRecommendationService.assess();
    }
}
