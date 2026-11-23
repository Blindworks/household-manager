package com.household.manager.charging;

import com.household.manager.common.DeliveryResult;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ChargingWatchEntry;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.push.PushNotificationService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.ChargingWatchRepository;
import com.household.manager.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingWatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T16:00:00Z");

    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private PushNotificationService pushService;
    @Mock
    private PushSubscriptionRepository subscriptionRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private ChargingWatchRepository watchRepository;

    /** Ersatz fuer die Tabelle; ueberlebt einen neuen Service wie die DB einen Neustart. */
    private final Map<ChargingWatchEntry.Key, ChargingWatchEntry> table = new LinkedHashMap<>();

    private final ChargingSnapshot snapshot = new ChargingSnapshot();
    private final MutableClock clock = new MutableClock(NOW);
    private ChargingWatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(watchRepository.save(any())).thenAnswer(i -> {
            ChargingWatchEntry e = i.getArgument(0);
            table.put(new ChargingWatchEntry.Key(e.getStationId(), e.getUserId()), e);
            return e;
        });
        lenient().when(watchRepository.findAllByOrderByCreatedAtAsc()).thenAnswer(i -> List.copyOf(table.values()));
        lenient().when(watchRepository.claim(anyString(), anyLong())).thenAnswer(i ->
                table.remove(new ChargingWatchEntry.Key(i.getArgument(0), i.getArgument(1))) == null ? 0 : 1);
        service = newService();
        lenient().when(subscriptionRepository.findAll()).thenReturn(List.of(subscription(1L), subscription(1L),
                subscription(3L)));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(
                user(1L, "Benedikt", true, false), user(3L, "Wandtablet", true, true)));
        lenient().when(pushService.sendToUser(anyLong(), anyString(), anyString())).thenReturn(DeliveryResult.ok());
        area("S1", 0, 2);
    }

    @Test
    void sendetEinenPushSobaldEinPunktFreiIst() {
        service.add("S1", 1L);
        service.checkWatches();
        verify(pushService, never()).sendToUser(anyLong(), anyString(), anyString());

        area("S1", 1, 2);
        service.checkWatches();

        verify(pushService).sendToUser(1L, "Ladesäule frei", "Am Südbahnhof S1: 1 von 2 Ladepunkten frei");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void sendetNurEinmal() {
        service.add("S1", 1L);
        area("S1", 2, 2);
        service.checkWatches();
        service.checkWatches();
        verify(pushService, times(1)).sendToUser(anyLong(), anyString(), anyString());
    }

    @Test
    void favoritenDetailsGehenVorDemUmkreisStand() {
        service.add("S1", 1L);
        snapshot.updateFavoriteDetails(Map.of("S1", new ChargingStationDetails("S1", List.of(
                new ChargePoint("P1", ChargePointStatus.FREE, 150.0, null, null, null)))), NOW);
        service.checkWatches();
        verify(pushService).sendToUser(1L, "Ladesäule frei", "Am Südbahnhof S1: 1 von 1 Ladepunkt frei");
    }

    @Test
    void wartetOhneBekanntenStand() {
        service.add("S1", 1L);
        snapshot.updateArea(List.of(), NOW);
        service.checkWatches();
        verify(pushService, never()).sendToUser(anyLong(), anyString(), anyString());
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void verfaelltNachVierStundenOhnePush() {
        service.add("S1", 1L);
        clock.set(NOW.plus(Duration.ofHours(4)));
        area("S1", 1, 2);
        service.checkWatches();
        verify(pushService, never()).sendToUser(anyLong(), anyString(), anyString());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void pushFehlerBeendetAuftragTrotzdem() {
        when(pushService.sendToUser(anyLong(), anyString(), anyString()))
                .thenReturn(DeliveryResult.failed("keine Push-Geräte"));
        service.add("S1", 1L);
        area("S1", 1, 2);
        service.checkWatches();
        assertThat(service.list()).isEmpty();
    }

    @Test
    void checkWatchesWirftNie() {
        service.add("S1", 1L);
        area("S1", 1, 2);
        when(pushService.sendToUser(anyLong(), anyString(), anyString())).thenThrow(new RuntimeException("kaputt"));
        service.checkWatches();
        assertThat(service.list()).isEmpty();
    }

    @Test
    void addLehntFreieStationAb() {
        area("S1", 1, 2);
        assertStatus(() -> service.add("S1", 1L), HttpStatus.CONFLICT);
    }

    @Test
    void addLehntUnbekannteStationAb() {
        assertStatus(() -> service.add("X", 1L), HttpStatus.NOT_FOUND);
    }

    @Test
    void addLehntSuperchargerAb() {
        assertStatus(() -> service.add("sc-1", 1L), HttpStatus.BAD_REQUEST);
    }

    @Test
    void addLehntPersonOhnePushGeraetAb() {
        assertStatus(() -> service.add("S1", 2L), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.add("S1", 3L), HttpStatus.BAD_REQUEST);
    }

    @Test
    void mehrerePersonenJeStationUndAbbestellen() {
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(1L, "Benedikt", true, false), user(2L, "Anna", true, false)));
        service.add("S1", 1L);
        service.add("S1", 2L);
        service.remove("S1", 1L);
        assertThat(service.list()).extracting(ChargingWatchService.ChargingWatch::displayName).containsExactly("Anna");
    }

    @Test
    void kandidatenOhneWandtabletUndOhneDeaktivierte() {
        when(userRepository.findAllById(any())).thenReturn(List.of(user(1L, "benedikt", true, false),
                user(2L, "Anna", true, false), user(3L, "Wandtablet", true, true), user(4L, "Alt", false, false)));
        assertThat(service.candidates()).extracting(ChargingWatchService.WatchCandidate::displayName)
                .containsExactly("Anna", "benedikt");
        verify(userRepository).findAllById(eq(java.util.Set.of(1L, 3L)));
    }

    @Test
    void auftragUeberlebtEinenNeustart() {
        service.add("S1", 1L);
        area("S1", 1, 2);

        newService().checkWatches();

        verify(pushService).sendToUser(1L, "Ladesäule frei", "Am Südbahnhof S1: 1 von 2 Ladepunkten frei");
    }

    @Test
    void sendetNichtWennDerAuftragInzwischenAbbestelltWurde() {
        service.add("S1", 1L);
        area("S1", 1, 2);
        doReturn(0).when(watchRepository).claim("S1", 1L);
        service.checkWatches();
        verify(pushService, never()).sendToUser(anyLong(), anyString(), anyString());
    }

    @Test
    void einFehlerStoertDieAnderenAuftraegeNicht() {
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(1L, "Benedikt", true, false), user(2L, "Anna", true, false)));
        service.add("S1", 1L);
        service.add("S1", 2L);
        area("S1", 1, 2);
        when(pushService.sendToUser(eq(1L), anyString(), anyString())).thenThrow(new RuntimeException("kaputt"));
        service.checkWatches();
        verify(pushService).sendToUser(eq(2L), anyString(), anyString());
    }

    @Test
    void checkWatchesWirftNichtBeiDatenbankfehler() {
        when(watchRepository.findAllByOrderByCreatedAtAsc()).thenThrow(new RuntimeException("DB weg"));
        service.checkWatches();
        verify(pushService, never()).sendToUser(anyLong(), anyString(), anyString());
    }

    private ChargingWatchService newService() {
        return new ChargingWatchService(snapshot, favoriteService, pushService, subscriptionRepository,
                userRepository, watchRepository, clock);
    }

    private void area(String id, int free, int total) {
        snapshot.updateArea(List.of(new ChargingStation(id, "Am Südbahnhof " + id, "Mainova", null, 50.1, 8.7,
                150.0, total, free)), NOW);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(status));
    }

    private static PushSubscription subscription(Long userId) {
        PushSubscription s = new PushSubscription();
        s.setUserId(userId);
        return s;
    }

    private static AppUser user(Long id, String name, boolean enabled, boolean wallTablet) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setDisplayName(name);
        u.setEnabled(enabled);
        u.setWallTablet(wallTablet);
        return u;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
