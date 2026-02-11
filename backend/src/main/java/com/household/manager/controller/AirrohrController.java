package com.household.manager.controller;

import com.household.manager.dto.AirrohrReadingResponse;
import com.household.manager.service.AirrohrService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for Airrohr fine dust data.
 * Base URL: /api/v1/airrohr
 */
@RestController
@RequestMapping("/v1/airrohr")
@RequiredArgsConstructor
public class AirrohrController {

    private final AirrohrService airrohrService;

    @GetMapping("/current")
    public AirrohrReadingResponse getCurrentReading() {
        return airrohrService.getCurrentReading();
    }
}
