package com.household.manager.controller;

import com.household.manager.dto.ImportSummaryResponse;
import com.household.manager.finance.CamtParseException;
import com.household.manager.service.StatementImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for importing bank statement files (camt.053).
 * Base URL: /api/v1/finance
 */
@RestController
@RequestMapping("/v1/finance")
@RequiredArgsConstructor
@Slf4j
public class StatementImportController {

    private final StatementImportService importService;

    @PostMapping("/import")
    public ResponseEntity<?> importStatement(
            @RequestParam("accountId") Long accountId,
            @RequestParam("file") MultipartFile file) {
        log.info("CAMT import request for account {}: {}", accountId, file.getOriginalFilename());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Die Datei ist leer.");
        }
        try {
            ImportSummaryResponse summary = importService.importStatement(
                    accountId, file.getOriginalFilename(), file.getInputStream());
            return ResponseEntity.ok(summary);
        } catch (CamtParseException ex) {
            log.warn("CAMT parse failed: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body("Die Datei konnte nicht als CAMT (camt.053) gelesen werden.");
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            log.error("Statement import failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Import fehlgeschlagen.");
        }
    }
}
