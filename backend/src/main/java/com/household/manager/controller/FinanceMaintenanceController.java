package com.household.manager.controller;

import com.household.manager.service.FinanceMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One-time maintenance endpoints for the finance module.
 *
 * <p>These endpoints exist to support schema or algorithm migrations and are not
 * part of the regular application API. Invoke them once after deploying a change
 * that requires backfilling existing data.
 */
@RestController
@RequestMapping("/v1/finance/maintenance")
@RequiredArgsConstructor
@Slf4j
public class FinanceMaintenanceController {

    private final FinanceMaintenanceService financeMaintenanceService;

    /**
     * Recomputes the dedup hash for all persisted transactions using the current composite algorithm.
     *
     * <p>Run once after deploying the fix for the recurring-payment deduplication bug.
     * A subsequent re-import will then correctly identify already-imported rows and
     * only insert the transactions that were previously collapsed.
     *
     * @return {@code {"recomputed": n}} where n is the number of updated rows
     */
    @PostMapping("/recompute-dedup")
    public ResponseEntity<Map<String, Integer>> recomputeDedupHashes() {
        int recomputed = financeMaintenanceService.recomputeDedupHashes();
        return ResponseEntity.ok(Map.of("recomputed", recomputed));
    }
}
