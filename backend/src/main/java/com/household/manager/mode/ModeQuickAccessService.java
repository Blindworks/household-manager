package com.household.manager.mode;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pflegt die Zeitfenster, in denen ein Eintrag der Modus-Leiste (Haus-Modus oder
 * hinzugeholter Helfer) im Tablet-Dashboard direkt als Knopf steht. Zulaessig sind nur
 * Leisten-Mitglieder ({@link HouseModeQueryService#listModes()}) — der Schnellzugriff ist
 * eine Teilmenge der Leiste. Die Auswertung selbst gehoert dem {@link ModeQuickAccessResolver}.
 */
@Service
@RequiredArgsConstructor
public class ModeQuickAccessService {

    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DUPLICATE_WINDOW_MESSAGE =
            "Fuer diesen Helfer gibt es bereits ein Zeitfenster.";

    private final ModeQuickAccessRepository repository;
    private final HouseModeQueryService houseModeQueryService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ModeQuickAccessDtos.Response> list() {
        Map<String, String> names = modeNames();
        return repository.findAllByOrderByIdAsc().stream()
                .map(window -> ModeQuickAccessDtos.Response.from(window, names.get(window.getEntityId())))
                .toList();
    }

    @Transactional
    public ModeQuickAccessDtos.Response create(ModeQuickAccessDtos.Request request) {
        // Einmal laden und weiterreichen: Validierung, Audit-Text und Antwort brauchen
        // dieselben Namen, drei getrennte Abfragen waeren reine Verschwendung.
        Map<String, String> names = modeNames();
        validate(request, names);
        if (repository.findByEntityId(request.entityId()).isPresent()) {
            throw new DuplicateEntityException(DUPLICATE_WINDOW_MESSAGE);
        }
        ModeQuickAccess saved = repository.save(ModeQuickAccess.builder()
                .entityId(request.entityId())
                .fromTime(request.fromTime())
                .toTime(request.toTime())
                .active(activeOrDefault(request))
                .build());
        auditService.record("mode.quick-access.create", auditDetail(saved, names));
        return ModeQuickAccessDtos.Response.from(saved, names.get(saved.getEntityId()));
    }

    @Transactional
    public ModeQuickAccessDtos.Response update(Long id, ModeQuickAccessDtos.Request request) {
        Map<String, String> names = modeNames();
        validate(request, names);
        ModeQuickAccess window = findOrThrow(id);
        // Die eigene Zeile darf beim Aendern nicht als Duplikat gelten.
        Optional<ModeQuickAccess> other = repository.findByEntityId(request.entityId())
                .filter(existing -> !existing.getId().equals(id));
        if (other.isPresent()) {
            throw new DuplicateEntityException(DUPLICATE_WINDOW_MESSAGE);
        }
        window.setEntityId(request.entityId());
        window.setFromTime(request.fromTime());
        window.setToTime(request.toTime());
        window.setActive(activeOrDefault(request));
        ModeQuickAccess saved = repository.save(window);
        auditService.record("mode.quick-access.update", auditDetail(saved, names));
        return ModeQuickAccessDtos.Response.from(saved, names.get(saved.getEntityId()));
    }

    @Transactional
    public void delete(Long id) {
        ModeQuickAccess window = findOrThrow(id);
        repository.delete(window);
        auditService.record("mode.quick-access.delete", auditDetail(window, modeNames()));
    }

    private ModeQuickAccess findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModeQuickAccess", "id", id));
    }

    /** Anzeigenamen der Eintraege der Modus-Leiste, nach Entity-ID. */
    private Map<String, String> modeNames() {
        return houseModeQueryService.listModes().stream()
                .collect(Collectors.toMap(ModeResponse::entityId, ModeResponse::displayName,
                        (first, second) -> first));
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default der Spalte (Muster Netzwerk-Geraete). */
    private boolean activeOrDefault(ModeQuickAccessDtos.Request request) {
        return request.active() == null || request.active();
    }

    private String auditDetail(ModeQuickAccess window, Map<String, String> names) {
        String name = Optional.ofNullable(names.get(window.getEntityId()))
                .orElse(window.getEntityId());
        if (window.isAlways()) {
            return "%s immer".formatted(name);
        }
        return "%s %s-%s".formatted(name, AUDIT_TIME.format(window.getFromTime()),
                AUDIT_TIME.format(window.getToTime()));
    }

    /**
     * Die Zeit-Checks stehen vor dem Helfer-Check: ein offensichtlich falsches Formular soll
     * die naheliegende Meldung bekommen.
     */
    private void validate(ModeQuickAccessDtos.Request request, Map<String, String> knownHelpers) {
        if (request.entityId() == null || request.entityId().isBlank()) {
            throw new IllegalArgumentException("Es ist kein Helfer ausgewaehlt.");
        }
        boolean always = request.fromTime() == null && request.toTime() == null;
        // Kein Fenster = immer anzeigen. Nur EIN gesetztes Ende waere mehrdeutig und
        // wird abgewiesen, statt still als "immer" oder "nie" gelesen zu werden.
        if (!always && (request.fromTime() == null || request.toTime() == null)) {
            throw new IllegalArgumentException(
                    "Beginn und Ende muessen beide gesetzt sein — oder beide leer fuer „immer anzeigen“.");
        }
        if (!always && request.fromTime().equals(request.toTime())) {
            throw new IllegalArgumentException(
                    "Beginn und Ende duerfen nicht gleich sein — das Fenster waere leer.");
        }
        if (!knownHelpers.containsKey(request.entityId())) {
            throw new IllegalArgumentException(
                    "%s steht nicht in der Modus-Leiste.".formatted(request.entityId()));
        }
    }
}
