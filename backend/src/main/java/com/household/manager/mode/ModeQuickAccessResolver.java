package com.household.manager.mode;

import com.household.manager.common.TimeWindow;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Einzige Definition von „dieser Haus-Modus ist jetzt faellig" (Muster
 * {@code TractiveHomeResolver}, {@code PowerConsumerQueryService.findConsumer}).
 *
 * <p><b>Wirft nie.</b> Ein Fehler beim Lesen der Fenster ergibt „nichts ist faellig"
 * plus Warnung im Log. Der Resolver reichert nur die Modus-Antwort an — eine kaputte
 * Konfigurationstabelle darf nicht die gesamte Modus-Leiste des Wandtablets mit einem
 * 500 ausknipsen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModeQuickAccessResolver {

    private final ModeQuickAccessRepository repository;
    private final Clock clock;

    /** Entity-IDs der Modi, deren Fenster gerade offen ist. Nie {@code null}. */
    @Transactional(readOnly = true)
    public Set<String> dueEntityIds() {
        LocalTime now = LocalTime.now(clock);
        try {
            return repository.findByActiveTrue().stream()
                    .filter(window -> isWithin(window, now))
                    .map(ModeQuickAccess::getEntityId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) {
            log.warn("Zeitfenster der Modus-Schnellzugriffe nicht lesbar, keiner gilt als faellig: {}",
                    ex.getMessage());
            return Set.of();
        }
    }

    /** Die Fensterregel selbst ist {@link TimeWindow} — geteilt mit dem Flow-Node {@code time-condition}. */
    private static boolean isWithin(ModeQuickAccess window, LocalTime now) {
        return new TimeWindow(window.getFromTime(), window.getToTime()).contains(now);
    }
}
