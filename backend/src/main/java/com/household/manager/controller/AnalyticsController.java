package com.household.manager.controller;

import com.household.manager.dto.OverviewResponse;
import com.household.manager.dto.TrendPoint;
import com.household.manager.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsService service;

    /** month format: yyyy-MM */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @RequestParam String month,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(service.overview(YearMonth.parse(month), accountId));
    }

    /** from/to format: yyyy-MM */
    @GetMapping("/trend")
    public ResponseEntity<List<TrendPoint>> trend(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Long accountId) {
        return ResponseEntity.ok(service.trend(YearMonth.parse(from), YearMonth.parse(to), accountId));
    }
}
