package com.household.manager.calendar;

import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
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
 * Kalender-Kategorien. Lesen darf jeder angemeldete Nutzer (auch das KIOSK-Wandtablet
 * braucht Namen und Farben zum Rendern), schreiben nur ADMIN — siehe SecurityConfig.
 */
@RestController
@RequestMapping("/v1/calendar/categories")
@RequiredArgsConstructor
public class CalendarCategoryController {

    private final CalendarCategoryService service;

    @GetMapping
    public ResponseEntity<List<CalendarCategoryResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<CalendarCategoryResponse> create(
            @RequestBody CalendarCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CalendarCategoryResponse> update(@PathVariable Long id,
            @RequestBody CalendarCategoryRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
