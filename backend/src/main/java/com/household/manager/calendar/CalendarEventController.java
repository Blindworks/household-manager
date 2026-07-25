package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Haushaltskalender: Termine, Serien und expandierte Vorkommen.
 * Basis-URL: /api/v1/calendar
 */
@RestController
@RequestMapping("/v1/calendar")
@RequiredArgsConstructor
public class CalendarEventController {

    private final CalendarEventService service;

    /** Expandierte Vorkommen im Zeitraum — die eine Abfrage der Monatsansicht. */
    @GetMapping("/events")
    public ResponseEntity<List<CalendarOccurrenceResponse>> getOccurrences(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getOccurrences(from, to));
    }

    /** Die naechsten Vorkommen ab jetzt (Intelligence Hub). */
    @GetMapping("/upcoming")
    public ResponseEntity<List<CalendarOccurrenceResponse>> getUpcoming(
            @RequestParam(defaultValue = "3") int limit) {
        return ResponseEntity.ok(service.getUpcoming(limit));
    }

    /** Stammdaten eines Termins/einer Serie (fuer den Bearbeiten-Dialog). */
    @GetMapping("/events/{id}")
    public ResponseEntity<CalendarEventResponse> getEvent(@PathVariable Long id) {
        return ResponseEntity.ok(service.getEvent(id));
    }

    @PostMapping("/events")
    public ResponseEntity<CalendarEventResponse> create(@RequestBody CalendarEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<CalendarEventResponse> update(@PathVariable Long id,
            @RequestBody CalendarEventRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Nur dieses Vorkommen loeschen (EXDATE am Master). */
    @DeleteMapping("/events/{id}/occurrences/{date}")
    public ResponseEntity<Void> deleteOccurrence(@PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteOccurrence(id, date);
        return ResponseEntity.noContent().build();
    }

    /** Nur dieses Vorkommen aendern (Override anlegen/aktualisieren). */
    @PutMapping("/events/{id}/occurrences/{date}")
    public ResponseEntity<CalendarOccurrenceResponse> updateOccurrence(@PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody CalendarEventRequest request) {
        return ResponseEntity.ok(service.updateOccurrence(id, date, request));
    }
}
