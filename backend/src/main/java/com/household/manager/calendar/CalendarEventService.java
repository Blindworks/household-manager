package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;

/**
 * CRUD und Occurrence-Aufloesung des Haushaltskalenders. Die {@link Clock} ist
 * injiziert, damit "heute" in Tests deterministisch ist (Muster WasteCollectionService).
 */
@Service
@Slf4j
public class CalendarEventService {

    private final CalendarEventRepository repository;
    private final RecurrenceExpansionService expansionService;
    private final Clock clock;

    public CalendarEventService(CalendarEventRepository repository,
                                RecurrenceExpansionService expansionService,
                                Clock clock) {
        this.repository = repository;
        this.expansionService = expansionService;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    @Transactional(readOnly = true)
    public CalendarEventResponse getEvent(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public CalendarEventResponse create(CalendarEventRequest request) {
        validate(request);
        return toResponse(repository.save(applyRequest(request, new CalendarEvent())));
    }

    @Transactional
    public CalendarEventResponse update(Long id, CalendarEventRequest request) {
        validate(request);
        return toResponse(repository.save(applyRequest(request, findOrThrow(id))));
    }

    @Transactional
    public void delete(Long id) {
        CalendarEvent event = findOrThrow(id);
        repository.deleteByRecurringParentId(id);
        repository.delete(event);
    }

    private CalendarEvent findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Termin %d existiert nicht.".formatted(id)));
    }

    private CalendarEvent applyRequest(CalendarEventRequest request, CalendarEvent event) {
        event.setTitle(request.getTitle().trim());
        event.setNotes(request.getNotes());
        event.setCategory(request.getCategory());
        event.setAllDay(request.isAllDay());
        event.setStartDate(request.getStartDate());
        event.setStartTime(request.isAllDay() ? null : request.getStartTime());
        event.setEndTime(request.isAllDay() ? null : request.getEndTime());
        event.setEndDate(request.getEndDate());
        event.setRrule(request.getRrule() != null && !request.getRrule().isBlank()
                ? request.getRrule() : null);
        return event;
    }

    private void validate(CalendarEventRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Titel darf nicht leer sein.");
        }
        if (request.getCategory() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Kategorie fehlt.");
        }
        if (request.getStartDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Startdatum fehlt.");
        }
        if (!request.isAllDay() && request.getStartTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ein Termin mit Uhrzeit braucht eine Start-Uhrzeit.");
        }
        if (!request.isAllDay() && request.getStartTime() != null && request.getEndTime() != null
                && request.getEndTime().isBefore(request.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Ende darf nicht vor dem Start liegen.");
        }
        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Enddatum darf nicht vor dem Startdatum liegen.");
        }
        if (request.getRrule() != null && !request.getRrule().isBlank()) {
            expansionService.validate(request.getRrule());
        }
    }

    private CalendarEventResponse toResponse(CalendarEvent event) {
        return CalendarEventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .notes(event.getNotes())
                .category(event.getCategory())
                .allDay(event.isAllDay())
                .startDate(event.getStartDate())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate())
                .rrule(event.getRrule())
                .recurring(event.isRecurring())
                .build();
    }
}
