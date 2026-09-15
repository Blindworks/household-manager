package com.household.manager.mode;

import com.household.manager.dto.ModeResponse;
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
 * Pflege-API der Schnellzugriff-Zeitfenster (ADMIN-only, siehe SecurityConfig) plus der
 * KIOSK-lesbare Abruf {@code GET /due}, aus dem das Tablet-Dashboard seine Knoepfe baut.
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
    private final ModeQuickAccessResolver resolver;

    /**
     * Die gerade faelligen Helfer. Der einzige nicht-ADMIN-Pfad dieses Controllers — die
     * Freigabe fuer KIOSK steht in SecurityConfig VOR dem ADMIN-Matcher des Pfadpraefixes.
     */
    @GetMapping("/due")
    public ResponseEntity<List<ModeResponse>> due() {
        return ResponseEntity.ok(resolver.dueEntities());
    }

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
