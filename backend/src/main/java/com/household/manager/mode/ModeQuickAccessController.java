package com.household.manager.mode;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pflege-API der Modus-Zeitfenster (ADMIN-only, siehe SecurityConfig).
 *
 * <p>Bewusst ein eigener Pfad statt {@code /v1/modes/quick-access}: unter {@code /v1/modes}
 * steht bereits {@code {entityId}}, und eine Pfad-Kollision dieser Art hat sich bei den
 * Zaehlerstaenden ({@code /series} vs. {@code /{type}}) schon einmal als Falle erwiesen.
 */
@RestController
@RequestMapping("/v1/mode-quick-access")
@RequiredArgsConstructor
public class ModeQuickAccessController {

    private final ModeQuickAccessService service;

    @GetMapping
    public ResponseEntity<List<ModeQuickAccessDtos.Response>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<ModeQuickAccessDtos.Response> create(
            @RequestBody ModeQuickAccessDtos.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModeQuickAccessDtos.Response> update(
            @PathVariable Long id, @RequestBody ModeQuickAccessDtos.Request request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
