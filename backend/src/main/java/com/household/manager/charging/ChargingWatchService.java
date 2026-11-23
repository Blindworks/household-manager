package com.household.manager.charging;

import com.household.manager.common.DeliveryResult;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.push.PushNotificationService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * „Benachrichtigen, wenn frei“ (Spec 2026-11-23): Warteauftraege je Station und Person, nur im Speicher.
 * Die Polls rufen {@link #checkWatches()}; ist ein Ladepunkt frei, geht genau ein Push an die Person
 * und der Auftrag endet. Nach {@link #MAX_AGE} verfaellt er still. Ein Neustart verwirft alle Auftraege.
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
    private final Clock clock;

    /** Schluessel: stationId + "|" + userId. */
    private final Map<String, ChargingWatch> watches = new LinkedHashMap<>();

    public ChargingWatchService(ChargingSnapshot snapshot, ChargingFavoriteService favoriteService,
                                PushNotificationService pushService,
                                PushSubscriptionRepository subscriptionRepository,
                                AppUserRepository userRepository, Clock clock) {
        this.snapshot = snapshot;
        this.favoriteService = favoriteService;
        this.pushService = pushService;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public record ChargingWatch(String stationId, String stationName, Long userId, String displayName,
                                Instant createdAt) {
    }

    public record WatchCandidate(Long userId, String displayName) {
    }

    public synchronized List<ChargingWatch> list() {
        return List.copyOf(watches.values());
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

    public synchronized void add(String stationId, Long userId) {
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
        watches.put(key(stationId, userId), new ChargingWatch(stationId, stationName(stationId), userId,
                person.displayName(), clock.instant()));
        log.info("Lade-Waechter: {} wartet auf {}", person.displayName(), stationId);
    }

    public synchronized void remove(String stationId, Long userId) {
        watches.remove(key(stationId, userId));
    }

    /** Aufgerufen am Ende jedes Polls; wirft nie, der Poll darf daran nicht scheitern. */
    public void checkWatches() {
        try {
            for (ChargingWatch watch : due()) {
                notify(watch);
            }
        } catch (RuntimeException ex) {
            log.warn("Lade-Waechter fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /** Entfernt verfallene und faellige Auftraege unter der Sperre; versendet wird danach ohne Sperre. */
    private synchronized List<ChargingWatch> due() {
        Instant now = clock.instant();
        List<ChargingWatch> due = new ArrayList<>();
        watches.values().removeIf(watch -> {
            if (!now.isBefore(watch.createdAt().plus(MAX_AGE))) {
                log.info("Lade-Waechter fuer {} ({}) nach {} h verfallen", watch.stationId(),
                        watch.displayName(), MAX_AGE.toHours());
                return true;
            }
            Optional<ChargingSnapshot.Availability> availability = snapshot.availability(watch.stationId());
            if (availability.isPresent() && availability.get().free() > 0) {
                due.add(watch);
                return true;
            }
            return false;
        });
        return due;
    }

    private void notify(ChargingWatch watch) {
        ChargingSnapshot.Availability a = snapshot.availability(watch.stationId())
                .orElse(new ChargingSnapshot.Availability(1, 0));
        DeliveryResult result = pushService.sendToUser(watch.userId(), PUSH_TITLE, message(watch.stationName(), a));
        if (!result.delivered()) {
            log.warn("Lade-Waechter: Push an {} fuer {} fehlgeschlagen: {}", watch.displayName(),
                    watch.stationId(), result.failure());
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

    private static String key(String stationId, Long userId) {
        return stationId + "|" + userId;
    }
}
