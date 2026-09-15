package com.household.manager.mode;

import com.household.manager.common.TimeWindow;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.ModeQuickAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Einzige Definition von „dieser Helfer ist jetzt faellig" (Muster
 * {@code TractiveHomeResolver}, {@code PowerConsumerQueryService.findConsumer}).
 *
 * <p>Seit 2026-09-15 gilt das fuer jeden Helfer vom Typ INPUT_BOOLEAN, nicht nur fuer
 * Haus-Modi. Das Tablet-Dashboard fragt {@link #dueEntities()} direkt ab
 * ({@code GET /v1/mode-quick-access/due}); die Modus-Liste traegt kein Flag mehr.
 *
 * <p><b>Wirft nie.</b> Ein Fehler beim Lesen ergibt „nichts ist faellig" plus Warnung im
 * Log — eine kaputte Konfigurationstabelle darf das Wandtablet nicht mit einem 500
 * ausknipsen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModeQuickAccessResolver {

    private final ModeQuickAccessRepository repository;
    private final EntityStateRepository entityStateRepository;
    private final ModeResponseMapper modeResponseMapper;
    private final Clock clock;

    /** Entity-IDs der Helfer, deren Fenster gerade offen ist. Nie {@code null}. */
    @Transactional(readOnly = true)
    public Set<String> dueEntityIds() {
        LocalTime now = LocalTime.now(clock);
        try {
            return repository.findByActiveTrue().stream()
                    .filter(window -> isWithin(window, now))
                    .map(ModeQuickAccess::getEntityId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) {
            log.warn("Zeitfenster der Schnellzugriffe nicht lesbar, keiner gilt als faellig: {}",
                    ex.getMessage());
            return Set.of();
        }
    }

    /**
     * Die gerade faelligen Helfer mit Name, Icon und Zustand, in Entity-ID-Reihenfolge.
     * Ein Fenster fuer eine geloeschte Entity wird still uebersprungen — es gibt nichts,
     * was der Knopf schalten koennte. Nie {@code null}.
     */
    @Transactional(readOnly = true)
    public List<ModeResponse> dueEntities() {
        Set<String> due = dueEntityIds();
        if (due.isEmpty()) {
            return List.of();
        }
        try {
            return entityStateRepository
                    .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                    .stream()
                    .filter(entity -> due.contains(entity.getEntityId()))
                    .map(modeResponseMapper::toResponse)
                    .toList();
        } catch (Exception ex) {
            log.warn("Faellige Schnellzugriffe nicht lesbar, keiner wird angezeigt: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Die Fensterregel selbst ist {@link TimeWindow} — geteilt mit dem Flow-Node {@code time-condition}. */
    private static boolean isWithin(ModeQuickAccess window, LocalTime now) {
        return new TimeWindow(window.getFromTime(), window.getToTime()).contains(now);
    }
}
