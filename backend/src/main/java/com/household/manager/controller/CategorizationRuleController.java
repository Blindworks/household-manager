package com.household.manager.controller;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.dto.RuleChangeRequest;
import com.household.manager.dto.RuleReevaluationPreview;
import com.household.manager.service.CategorizationRuleService;
import com.household.manager.service.RuleReevaluationService;
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
    private final RuleReevaluationService reevaluationService;

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
    public ResponseEntity<Map<String, Integer>> delete(
            @PathVariable Long id, @RequestParam(defaultValue = "false") boolean apply) {
        return ResponseEntity.ok(Map.of("applied", service.delete(id, apply)));
    }

    @PostMapping("/preview")
    public ResponseEntity<RuleReevaluationPreview> preview(@Valid @RequestBody RuleChangeRequest change) {
        return ResponseEntity.ok(reevaluationService.preview(change));
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Integer>> applyAll() {
        return ResponseEntity.ok(Map.of("applied", service.applyAll()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
