package com.household.manager.grocery;

import com.household.manager.grocery.dto.RunStartedDto;
import com.household.manager.grocery.dto.RunStatusDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * „Jetzt suchen“ für alle Mitglieder (Angebote-Seite und Admin-Seite): startet den vollen Lauf im Hintergrund,
 * höchstens alle 10 Minuten (sonst 429). MEMBER über den Matcher {@code /v1/groceries/**}.
 */
@RestController
@RequestMapping("/v1/groceries/runs")
public class GroceryRunController {

    private final GroceryRunService runService;
    private final GroceryProperties properties;

    public GroceryRunController(GroceryRunService runService, GroceryProperties properties) {
        this.runService = runService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<RunStartedDto> runNow() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Supermarkt-Angebote sind deaktiviert");
        }
        runService.runManually();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunStartedDto("gestartet"));
    }

    /** Laufstand für das Polling der Seiten. */
    @GetMapping("/status")
    public RunStatusDto runStatus() {
        return runService.runStatus();
    }
}
