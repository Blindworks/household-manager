package com.household.manager.laundry;

import com.household.manager.exception.ErrorResponse;
import com.household.manager.repository.LaundryDryingSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** REST-Endpunkte der Waeschetrocknung im Keller (Wandtablet-Fragen, Zustand, Kontrolle). */
@RestController
@RequestMapping("/v1/laundry")
@RequiredArgsConstructor
@Slf4j
public class LaundryController {

    private final LaundryDryingService service;
    private final LaundryDryingSessionRepository repository;

    @GetMapping("/state")
    public LaundryStateView state() {
        return service.currentState();
    }

    @PostMapping("/answers/hung")
    public LaundryStateView answerHung(@RequestBody LaundryAnswerRequest request) {
        service.answerHung(request.yes());
        return service.currentState();
    }

    @PostMapping("/answers/dry")
    public LaundryStateView answerDry(@RequestBody LaundryAnswerRequest request) {
        service.answerDry(request.yes());
        return service.currentState();
    }

    @PostMapping("/removed")
    public LaundryStateView removed() {
        service.markRemoved();
        return service.currentState();
    }

    @GetMapping("/sessions")
    public LaundrySessionsView sessions() {
        var entries = repository.findTop10ByStatusInOrderByAskedAtDesc(LaundrySessionStatus.FINISHED).stream()
                .map(s -> new LaundrySessionsView.Entry(s.getAskedAt(), s.getStartedAt(), s.getEndedAt(),
                        s.getStatus(), s.getConfirmedLoad(), s.getWeakLoad(), s.getLowerBoundLoad()))
                .toList();
        return new LaundrySessionsView(service.requiredLoad(), entries);
    }

    /** Nur fuer diesen Controller: das globale IllegalState-Mapping (400) bleibt unberuehrt. */
    @ExceptionHandler({LaundryConflictException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex) {
        log.warn("Waesche-Konflikt: {}", ex.getMessage());
        String message = ex instanceof LaundryConflictException
                ? ex.getMessage() : "Der Waschgang wurde gleichzeitig geaendert, bitte erneut versuchen.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message(message)
                .build());
    }
}
