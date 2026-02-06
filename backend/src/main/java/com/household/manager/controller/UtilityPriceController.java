package com.household.manager.controller;

import com.household.manager.dto.UtilityPriceRequest;
import com.household.manager.dto.UtilityPriceResponse;
import com.household.manager.model.entity.MeterType;
import com.household.manager.service.UtilityPriceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for utility price operations.
 * <p>
 * Provides endpoints for creating, retrieving, and managing
 * utility prices for electricity and water with validity periods.
 * <p>
 * Base URL: /api/v1/utility-prices
 */
@RestController
@RequestMapping("/v1/utility-prices")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:4200"})
public class UtilityPriceController {

    private final UtilityPriceService utilityPriceService;

    /**
     * Create a new utility price.
     * <p>
     * POST /api/v1/utility-prices
     *
     * @param request validated utility price request
     * @return created utility price with HTTP 201 status
     */
    @PostMapping
    public ResponseEntity<UtilityPriceResponse> createUtilityPrice(
            @Valid @RequestBody UtilityPriceRequest request) {
        log.info("Received request to create utility price for type: {}", request.getMeterType());
        UtilityPriceResponse response = utilityPriceService.createUtilityPrice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all utility prices across all meter types.
     * <p>
     * GET /api/v1/utility-prices
     *
     * @return list of all utility prices
     */
    @GetMapping
    public ResponseEntity<List<UtilityPriceResponse>> getAllUtilityPrices() {
        log.info("Received request to get all utility prices");
        List<UtilityPriceResponse> prices = utilityPriceService.getAllUtilityPrices();
        return ResponseEntity.ok(prices);
    }

    /**
     * Get all utility prices for a specific meter type.
     * <p>
     * GET /api/v1/utility-prices/{type}
     *
     * @param type the meter type (ELECTRICITY or GAS)
     * @return list of utility prices for the specified type
     */
    @GetMapping("/{type}")
    public ResponseEntity<List<UtilityPriceResponse>> getUtilityPricesByType(
            @PathVariable MeterType type) {
        log.info("Received request to get utility prices for type: {}", type);
        List<UtilityPriceResponse> prices = utilityPriceService.getUtilityPricesByMeterType(type);
        return ResponseEntity.ok(prices);
    }

    /**
     * Get the current price for a specific meter type.
     * <p>
     * GET /api/v1/utility-prices/{type}/current
     * <p>
     * Returns the price valid for today's date.
     *
     * @param type the meter type (ELECTRICITY or GAS)
     * @return the current utility price for the specified type
     */
    @GetMapping("/{type}/current")
    public ResponseEntity<UtilityPriceResponse> getCurrentPrice(
            @PathVariable MeterType type) {
        log.info("Received request to get current price for type: {}", type);
        UtilityPriceResponse price = utilityPriceService.getCurrentPriceForMeterType(type);
        return ResponseEntity.ok(price);
    }

    /**
     * Delete a utility price by ID.
     * <p>
     * DELETE /api/v1/utility-prices/{id}
     *
     * @param id the ID of the utility price to delete
     * @return HTTP 204 No Content on success
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUtilityPrice(@PathVariable Long id) {
        log.info("Received request to delete utility price with ID: {}", id);
        utilityPriceService.deleteUtilityPrice(id);
        return ResponseEntity.noContent().build();
    }
}
