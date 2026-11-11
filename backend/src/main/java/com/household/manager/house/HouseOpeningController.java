package com.household.manager.house;

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
 * Fenster und Tueren des Digitalen Zwillings. Lesen: alle Angemeldeten inkl. KIOSK (generische
 * GET-Regel); Anlegen, Aendern, Loeschen: ADMIN (eigene Matcher in {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/v1/house/openings")
@RequiredArgsConstructor
public class HouseOpeningController {

    private final HouseOpeningService service;

    @GetMapping
    public List<HouseOpeningDtos.Response> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<HouseOpeningDtos.Response> create(
            @RequestBody(required = false) HouseOpeningDtos.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(requireBody(request)));
    }

    @PutMapping("/{id}")
    public HouseOpeningDtos.Response update(@PathVariable long id,
                                            @RequestBody(required = false) HouseOpeningDtos.Request request) {
        return service.update(id, requireBody(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static HouseOpeningDtos.Request requireBody(HouseOpeningDtos.Request request) {
        if (request == null) {
            throw new IllegalArgumentException("Angaben fehlen.");
        }
        return request;
    }
}
