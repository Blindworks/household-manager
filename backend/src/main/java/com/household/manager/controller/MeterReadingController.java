package com.household.manager.controller;

import com.household.manager.dto.ConsumptionResponse;
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.dto.MeterReadingCreateResponse;
import com.household.manager.dto.MeterReadingImportResponse;
import com.household.manager.dto.MeterReadingRequest;
import com.household.manager.dto.MeterReadingResponse;
import com.household.manager.importer.MeterReadingCsvImporter;
import com.household.manager.model.entity.MeterType;
import com.household.manager.service.ConsumptionRange;
import com.household.manager.service.MeterConsumptionSeriesService;
import com.household.manager.service.MeterReadingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * REST Controller for meter reading operations.
 * <p>
 * Provides endpoints for creating, retrieving, and analyzing
 * household utility meter readings (electricity, gas, water).
 * <p>
 * Base URL: /api/v1/meter-readings
 */
@RestController
@RequestMapping("/v1/meter-readings")
@RequiredArgsConstructor
@Slf4j
public class MeterReadingController {

    private final MeterReadingService meterReadingService;
    private final MeterReadingCsvImporter meterReadingCsvImporter;
    private final MeterConsumptionSeriesService meterConsumptionSeriesService;

    /**
     * Create a new meter reading.
     * <p>
     * POST /api/v1/meter-readings
     *
     * @param request validated meter reading request
     * @return created meter reading with HTTP 201 status
     */
    @PostMapping
    public ResponseEntity<MeterReadingCreateResponse> createMeterReading(
            @Valid @RequestBody MeterReadingRequest request) {
        log.info("Received request to create meter reading for type: {}", request.getMeterType());
        MeterReadingCreateResponse response = meterReadingService.createMeterReading(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all meter readings across all meter types.
     * <p>
     * GET /api/v1/meter-readings
     *
     * @return list of all meter readings
     */
    @GetMapping
    public ResponseEntity<List<MeterReadingResponse>> getAllMeterReadings() {
        log.info("Received request to get all meter readings");
        List<MeterReadingResponse> readings = meterReadingService.getAllMeterReadings();
        return ResponseEntity.ok(readings);
    }

    /**
     * Get all meter readings for a specific meter type.
     * <p>
     * GET /api/v1/meter-readings/{type}
     *
     * @param type the meter type (ELECTRICITY, GAS, or WATER)
     * @return list of meter readings for the specified type
     */
    @GetMapping("/{type}")
    public ResponseEntity<List<MeterReadingResponse>> getMeterReadingsByType(
            @PathVariable MeterType type) {
        log.info("Received request to get meter readings for type: {}", type);
        List<MeterReadingResponse> readings = meterReadingService.getMeterReadingsByType(type);
        return ResponseEntity.ok(readings);
    }

    /**
     * Get the most recent meter reading for a specific meter type.
     * <p>
     * GET /api/v1/meter-readings/{type}/latest
     *
     * @param type the meter type
     * @return the latest meter reading for the specified type
     */
    @GetMapping("/{type}/latest")
    public ResponseEntity<MeterReadingResponse> getLatestReading(
            @PathVariable MeterType type) {
        log.info("Received request to get latest reading for type: {}", type);
        MeterReadingResponse reading = meterReadingService.getLatestReading(type);
        return ResponseEntity.ok(reading);
    }

    /**
     * Calculate consumption between the two most recent readings.
     * <p>
     * GET /api/v1/meter-readings/{type}/consumption
     * <p>
     * Returns detailed consumption information including:
     * - Current and previous reading values
     * - Total consumption
     * - Days between readings
     * - Average daily consumption
     *
     * @param type the meter type
     * @return consumption details for the specified meter type
     */
    @GetMapping("/{type}/consumption")
    public ResponseEntity<ConsumptionResponse> calculateConsumption(
            @PathVariable MeterType type) {
        log.info("Received request to calculate consumption for type: {}", type);
        ConsumptionResponse consumption = meterReadingService.calculateConsumption(type);
        return ResponseEntity.ok(consumption);
    }

    /**
     * Consumption series for the wall tablet view, aggregated per week or month.
     * <p>
     * GET /api/v1/meter-readings/series?range=WEEKS_26
     * <p>
     * The resolution (week or month) is part of the range value, so a single
     * parameter cannot express an impossible combination such as "8 weeks, monthly".
     *
     * @param range the selected time window, defaults to 26 weeks
     * @return one series per meter type that has readings in the window
     */
    @GetMapping("/series")
    public ResponseEntity<List<MeterConsumptionSeries>> getConsumptionSeries(
            @RequestParam(required = false, defaultValue = "WEEKS_26") ConsumptionRange range) {
        // Bewusst debug statt info wie die uebrigen Endpunkte dieser Datei: das
        // Wandtablet ruft diese Reihe alle fuenf Minuten dauerhaft ab und wuerde das
        // Log sonst zumuellen.
        log.debug("Received request for consumption series, range: {}", range);
        return ResponseEntity.ok(meterConsumptionSeriesService.getSeries(range));
    }

    /**
     * Import meter readings from CSV upload.
     * <p>
     * POST /api/v1/meter-readings/import
     *
     * @param file CSV file upload
     * @return import result with created count
     */
    @PostMapping("/import")
    public ResponseEntity<MeterReadingImportResponse> importMeterReadings(
            @RequestParam("file") MultipartFile file) {
        log.info("Received CSV import request: {}", file.getOriginalFilename());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(MeterReadingImportResponse.builder().createdCount(0).build());
        }

        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            int created = meterReadingCsvImporter.importFromReader(reader);
            return ResponseEntity.ok(MeterReadingImportResponse.builder().createdCount(created).build());
        } catch (Exception ex) {
            log.error("CSV import failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(MeterReadingImportResponse.builder().createdCount(0).build());
        }
    }

    /**
     * Exception handler for IllegalArgumentException.
     * <p>
     * Handles validation errors such as reading values being less than previous readings.
     *
     * @param ex the exception
     * @return error response with HTTP 400 status
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
