package com.household.manager.mode;

import com.household.manager.common.TimeWindow;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.model.entity.ModeQuickAccess;
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
 * <p>Der Schnellzugriff ist eine <b>Teilmenge der Modus-Leiste</b>: {@link #dueEntities()}
 * filtert {@link HouseModeQueryService#listModes()} auf die offenen Fenster. Ein Fenster
 * fuer einen Helfer, der (nicht mehr) in der Leiste steht, ist damit wirkungslos — die
 * Admin-Seite bietet deshalb nur Leisten-Mitglieder zur Auswahl an.
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
    private final HouseModeQueryService houseModeQueryService;
    private final Clock clock;

    /** Entity-IDs der Eintraege, deren Fenster gerade offen ist (oder die „immer" gelten). Nie {@code null}. */
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
     * Die gerade faelligen Helfer mit Name, Icon und Zustand, in der Reihenfolge der
     * Modus-Leiste. Ein Fenster fuer einen Helfer ausserhalb der Leiste (oder eine
     * geloeschte Entity) wird still uebersprungen. Nie {@code null}.
     */
    @Transactional(readOnly = true)
    public List<ModeResponse> dueEntities() {
        Set<String> due = dueEntityIds();
        if (due.isEmpty()) {
            return List.of();
        }
        try {
            return houseModeQueryService.listModes().stream()
                    .filter(mode -> due.contains(mode.entityId()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Faellige Schnellzugriffe nicht lesbar, keiner wird angezeigt: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Die Fensterregel selbst ist {@link TimeWindow} — geteilt mit dem Flow-Node
     * {@code time-condition}. Ohne Fenster gilt der Eintrag immer. Eine halb gesetzte Zeile
     * (nur Beginn oder nur Ende, per Hand eingetragen) gilt fail-safe als <b>nie</b> —
     * sonst erzeugte ein Tippfehler in der DB einen Dauerknopf.
     */
    private static boolean isWithin(ModeQuickAccess window, LocalTime now) {
        if (window.isAlways()) {
            return true;
        }
        if (window.getFromTime() == null || window.getToTime() == null) {
            return false;
        }
        return new TimeWindow(window.getFromTime(), window.getToTime()).contains(now);
    }
}
