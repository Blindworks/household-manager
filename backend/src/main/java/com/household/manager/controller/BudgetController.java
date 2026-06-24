package com.household.manager.controller;

import com.household.manager.dto.BudgetRequest;
import com.household.manager.dto.BudgetResponse;
import com.household.manager.dto.BudgetStatusResponse;
import com.household.manager.service.BudgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/budgets")
@RequiredArgsConstructor
@Slf4j
public class BudgetController {

    private final BudgetService service;

    @GetMapping
    public ResponseEntity<List<BudgetResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<BudgetResponse> save(@Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(service.save(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** month format: yyyy-MM, e.g. 2026-06 */
    @GetMapping("/status")
    public ResponseEntity<BudgetStatusResponse> status(@RequestParam String month) {
        return ResponseEntity.ok(service.getStatus(YearMonth.parse(month)));
    }
}
