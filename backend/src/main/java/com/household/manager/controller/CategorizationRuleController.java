package com.household.manager.controller;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.service.CategorizationRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/finance/rules")
@RequiredArgsConstructor
@Slf4j
public class CategorizationRuleController {

    private final CategorizationRuleService service;

    @GetMapping
    public ResponseEntity<List<CategorizationRuleResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @PostMapping
    public ResponseEntity<CategorizationRuleResponse> create(
            @Valid @RequestBody CategorizationRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CategorizationRuleResponse> update(
            @PathVariable Long id, @Valid @RequestBody CategorizationRuleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Integer>> applyAll() {
        return ResponseEntity.ok(Map.of("applied", service.applyAllToUncategorized()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
