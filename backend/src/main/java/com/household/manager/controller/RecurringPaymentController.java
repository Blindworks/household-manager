package com.household.manager.controller;

import com.household.manager.dto.RecurringDetectionResponse;
import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.service.RecurringDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/finance/recurring")
@RequiredArgsConstructor
@Slf4j
public class RecurringPaymentController {

    private final RecurringDetectionService service;

    @GetMapping
    public ResponseEntity<List<RecurringPaymentResponse>> list(
            @RequestParam(required = false) Boolean confirmed) {
        return ResponseEntity.ok(service.list(confirmed));
    }

    @PostMapping("/detect")
    public ResponseEntity<RecurringDetectionResponse> detect(@RequestParam Long accountId) {
        return ResponseEntity.ok(service.detect(accountId));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<RecurringPaymentResponse> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(service.confirm(id));
    }

    /** "Verwerfen": marks the row as dismissed; it stays stored but is never listed. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> dismiss(@PathVariable Long id) {
        service.dismiss(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
