package com.household.manager.charging;

import com.household.manager.common.DeliveryResult;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingWatchEntry;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.push.PushNotificationService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.ChargingWatchRepository;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * „Benachrichtigen, wenn frei“ (Spec 2026-11-23): Warteauftraege je Station und Person, in der Datenbank.
 * Die Polls rufen {@link #checkWatches()}; ist ein Ladepunkt frei, geht genau ein Push an die Person
 * und der Auftrag endet. Nach {@link #MAX_AGE} verfaellt er still. Ein Neustart behaelt sie; bis zum
 * ersten Poll ist der Stand unbekannt und es passiert nichts.
 */
@Service
@Slf4j
public class ChargingWatchService {

    static final Duration MAX_AGE = Duration.ofHours(4);
    static final String PUSH_TITLE = "Ladesäule frei";

    private final ChargingSnapshot snapshot;
    private final ChargingFavoriteService favoriteService;
    private final PushNotificationService pushService;
    private final PushSubscriptionRepository subscriptionRepository;
    private final AppUserRepository userRepository;
    private final ChargingWatchRepository watchRepository;
    private final Clock clock;

    public ChargingWatchService(ChargingSnapshot snapshot, ChargingFavoriteService favoriteService,
                                PushNotificationService pushService,
                                PushSubscriptionRepository subscriptionRepository,
                                AppUserRepository userRepository,
                                ChargingWatchRepository watchRepository, Clock clock) {
        this.snapshot = snapshot;
        this.favoriteService = favoriteService;
        this.pushService = pushService;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.watchRepository = watchRepository;
        this.clock = clock;
    }

    public record ChargingWatch(String stationId, String stationName, Long userId, String displayName,
                                Instant createdAt) {
    }

    public record WatchCandidate(Long userId, String displayName) {
    }

    public List<ChargingWatch> list() {
        List<ChargingWatchEntry> entries = watchRepository.findAllByOrderByCreatedAtAsc();
        Map<Long, String> names = userRepository
                .findAllById(entries.stream().map(ChargingWatchEntry::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        return entries.stream()
                .map(e -> new ChargingWatch(e.getStationId(), e.getStationName(), e.getUserId(),
                        names.getOrDefault(e.getUserId(), "Unbekannt"), e.getCreatedAt()))
                .toList();
    }

    /** Aktive Personen mit mindestens einem Push-Geraet, ohne Wandtablet-Konto, nach Namen sortiert. */
    public List<WatchCandidate> candidates() {
        Set<Long> withDevice = subscriptionRepository.findAll().stream()
                .map(PushSubscription::getUserId).collect(Collectors.toSet());
        return userRepository.findAllById(withDevice).stream()
                .filter(AppUser::isEnabled)
                .filter(u -> !u.isWallTablet())
                .map(u -> new WatchCandidate(u.getId(), u.getDisplayName()))
                .sorted(Comparator.comparing(WatchCandidate::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public void add(String stationId, Long userId) {
        if (stationId.startsWith(ChargingQueryService.SUPERCHARGER_ID_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supercharger haben keine Belegungsdaten");
        }
        WatchCandidate person = candidates().stream().filter(c -> c.userId().equals(userId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Diese Person hat kein Gerät mit Push-Benachrichtigungen."));
        ChargingSnapshot.Availability availability = snapshot.availability(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ladesäule unbekannt"));
        if (availability.free() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Es ist bereits ein Ladepunkt frei.");
        }
        // DATETIME kennt keine Bruchsekunden, MariaDB rundete sonst.
        watchRepository.save(new ChargingWatchEntry(stationId, userId, stationName(stationId),
                clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        log.info("Lade-Waechter: {} wartet auf {}", person.displayName(), stationId);
    }

    public void remove(String stationId, Long userId) {
        watchRepository.claim(stationId, userId);
    }

    /** Aufgerufen am Ende jedes Polls; wirft nie, der Poll darf daran nicht scheitern. */
    public void checkWatches() {
        try {
            Instant now = clock.instant();
            for (ChargingWatchEntry watch : watchRepository.findAllByOrderByCreatedAtAsc()) {
                checkSafely(watch, now);
            }
        } catch (RuntimeException ex) {
            log.warn("Lade-Waechter fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /** Ein Fehler bei einem Auftrag stoert die anderen nicht. */
    private void checkSafely(ChargingWatchEntry watch, Instant now) {
        try {
            check(watch, now);
        } catch (RuntimeException ex) {
            log.warn("Lade-Waechter fuer {} (Nutzer {}) fehlgeschlagen: {}", watch.getStationId(),
                    watch.getUserId(), ex.getMessage());
        }
    }

    private void check(ChargingWatchEntry watch, Instant now) {
        if (!now.isBefore(watch.getCreatedAt().plus(MAX_AGE))) {
            watchRepository.claim(watch.getStationId(), watch.getUserId());
            log.info("Lade-Waechter fuer {} (Nutzer {}) nach {} h verfallen", watch.getStationId(),
                    watch.getUserId(), MAX_AGE.toHours());
            return;
        }
        Optional<ChargingSnapshot.Availability> availability = snapshot.availability(watch.getStationId());
        if (availability.isEmpty() || availability.get().free() <= 0) {
            return;
        }
        // Erst entfernen, dann senden: beide Polls duerfen nicht doppelt melden, ein Abbestellen gewinnt.
        if (watchRepository.claim(watch.getStationId(), watch.getUserId()) == 1) {
            send(watch, availability.get());
        }
    }

    private void send(ChargingWatchEntry watch, ChargingSnapshot.Availability availability) {
        DeliveryResult result = pushService.sendToUser(watch.getUserId(), PUSH_TITLE,
                message(watch.getStationName(), availability));
        if (!result.delivered()) {
            log.warn("Lade-Waechter: Push an Nutzer {} fuer {} fehlgeschlagen: {}", watch.getUserId(),
                    watch.getStationId(), result.failure());
        }
    }

    static String message(String stationName, ChargingSnapshot.Availability a) {
        if (a.total() <= 0) {
            return stationName + ": ein Ladepunkt ist frei";
        }
        return stationName + ": " + a.free() + " von " + a.total() + (a.total() == 1 ? " Ladepunkt" : " Ladepunkten")
                + " frei";
    }

    private String stationName(String stationId) {
        return snapshot.station(stationId).map(ChargingStation::name)
                .or(() -> favoriteService.list().stream().filter(f -> f.getStationId().equals(stationId))
                        .map(ChargingFavorite::getDisplayName).findFirst())
                .orElse(stationId);
    }
}
