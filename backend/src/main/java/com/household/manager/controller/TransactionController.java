package com.household.manager.controller;

import com.household.manager.dto.CategorizeRequest;
import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.TransactionResponse;
import com.household.manager.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/v1/finance/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService service;

    @GetMapping
    public ResponseEntity<List<TransactionResponse>> list(
            @RequestParam(required = false) Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.list(accountId, from, to));
    }

    @PatchMapping("/{id}/category")
    public ResponseEntity<CategorizeResponse> categorize(
            @PathVariable Long id, @Valid @RequestBody CategorizeRequest request) {
        return ResponseEntity.ok(service.categorize(id, request.getCategoryId()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
