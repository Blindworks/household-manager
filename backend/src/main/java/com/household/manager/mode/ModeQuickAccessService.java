package com.household.manager.mode;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.EntityStateRepository;
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
 * Pflegt die Zeitfenster, in denen ein Helfer (INPUT_BOOLEAN, Quelle MANUAL — Haus-Modus
 * oder gewoehnlicher Helfer) im Tablet-Dashboard direkt als Knopf steht. Die Auswertung
 * selbst gehoert dem {@link ModeQuickAccessResolver}.
 */
@Service
@RequiredArgsConstructor
public class ModeQuickAccessService {

    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DUPLICATE_WINDOW_MESSAGE =
            "Fuer diesen Helfer gibt es bereits ein Zeitfenster.";

    private final ModeQuickAccessRepository repository;
    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;
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

    /** Anzeigenamen aller Helfer vom Typ INPUT_BOOLEAN (Modi eingeschlossen), nach Entity-ID. */
    private Map<String, String> modeNames() {
        return entityStateRepository
                .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                .stream()
                .collect(Collectors.toMap(EntityState::getEntityId, entityStateResponseMapper::displayName,
                        (first, second) -> first));
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default der Spalte (Muster Netzwerk-Geraete). */
    private boolean activeOrDefault(ModeQuickAccessDtos.Request request) {
        return request.active() == null || request.active();
    }

    private String auditDetail(ModeQuickAccess window, Map<String, String> names) {
        String name = Optional.ofNullable(names.get(window.getEntityId()))
                .orElse(window.getEntityId());
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
        if (request.fromTime() == null || request.toTime() == null) {
            throw new IllegalArgumentException("Beginn und Ende muessen gesetzt sein.");
        }
        if (request.fromTime().equals(request.toTime())) {
            throw new IllegalArgumentException(
                    "Beginn und Ende duerfen nicht gleich sein — das Fenster waere leer.");
        }
        if (!knownHelpers.containsKey(request.entityId())) {
            throw new IllegalArgumentException(
                    "%s ist kein Helfer vom Typ input_boolean.".formatted(request.entityId()));
        }
    }
}
