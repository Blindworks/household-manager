package com.household.manager.laundry;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.repository.LaundryDryingSessionRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaundryDryingServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final List<LaundryDryingSession> store = new ArrayList<>();
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 11, 10, 0));
    private final LaundryClimateReader climateReader = mock(LaundryClimateReader.class);
    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final AuditService auditService = mock(AuditService.class);
    private Optional<KellerClimate> climate = Optional.empty();
    private LaundryDryingService service;

    @BeforeEach
    void setUp() {
        LaundryDryingSessionRepository repository = fakeRepository();
        when(climateReader.current()).thenAnswer(inv -> climate);
        service = new LaundryDryingService(repository, climateReader,
                new LaundryEntityReporter(entityStateService), auditService, mock(SystemJobMonitor.class), clock);
    }

    // ---------- Frage "Waesche aufgehaengt?" ----------

    @Test
    void askLegtFrageAnUndMeldetAsking() {
        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);

        assertThat(service.currentState().status()).isEqualTo("asking");
        assertThat(service.currentState().prompt()).isEqualTo("HUNG");
        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(update.capture());
        EntityStateUpdate last = update.getValue();
        assertThat(last.entityId()).isEqualTo("sensor.laundry_drying");
        assertThat(last.state()).isEqualTo("asking");
        assertThat(last.attributes()).doesNotContainKey("predictedDryAt").doesNotContainValue(null);
    }

    @Test
    void zweiteFrageWaehrendOffenemWaschgangWirdIgnoriert() {
        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);
        service.ask(LaundryTrigger.HUMIDITY_RISE, 60.0);

        assertThat(store).hasSize(1);
        assertThat(store.get(0).getTrigger()).isEqualTo(LaundryTrigger.SWITCH_OFF);
    }

    @Test
    void neinSperrtBisZurNaechstenWaescheUndOnNewWashHebtDieSperreAuf() {
        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);
        service.answerHung(false);
        clock.advance(Duration.ofMinutes(30));
        service.ask(LaundryTrigger.HUMIDITY_RISE, 58.9);

        assertThat(store).hasSize(1);
        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.DECLINED);
        verify(auditService).record("laundry.answer.hung", "nein");

        clock.advance(Duration.ofHours(2));
        service.onNewWash();
        clock.advance(Duration.ofHours(2));
        service.ask(LaundryTrigger.SWITCH_OFF, 57.0);

        assertThat(store).hasSize(2);
        assertThat(service.currentState().status()).isEqualTo("asking");
    }

    @Test
    void frageVerfaelltNachSechsStunden() {
        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);
        clock.advance(Duration.ofHours(6).minusMinutes(1));
        service.tick();
        assertThat(service.currentState().status()).isEqualTo("asking");

        clock.advance(Duration.ofMinutes(1));
        service.tick();

        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.EXPIRED);
        assertThat(service.currentState().status()).isEqualTo("idle");
    }

    @Test
    void jaStartetTrocknenMitGrundfeuchte() {
        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);
        clock.advance(Duration.ofMinutes(5));
        service.answerHung(true);

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.DRYING);
        assertThat(session.getStartedAt()).isEqualTo(clock.now());
        assertThat(session.getBaselineHumidity()).isEqualTo(58.9);
        assertThat(service.currentState().status()).isEqualTo("drying");
        assertThat(service.currentState().prompt()).isEqualTo("NONE");
        verify(auditService).record("laundry.answer.hung", "ja");
    }

    // ---------- Schaetzung ----------

    @Test
    void tickSummiertVpdMalZeit() {
        startDrying(50.0);
        runMinutes(60, 16, 60);

        assertThat(store.get(0).getAccumulatedLoad()).isCloseTo(0.73, within(0.01));
    }

    @Test
    void veralteterFuehlerPausiertOhneZaehlen() {
        startDrying(50.0);
        climate = Optional.empty();
        for (int i = 0; i < 90; i++) {
            clock.advance(Duration.ofMinutes(1));
            service.tick();
        }

        LaundryStateView view = service.currentState();
        assertThat(view.estimatePaused()).isTrue();
        assertThat(view.predictedDryAt()).isNull();
        assertThat(view.status()).isEqualTo("drying");
        assertThat(store.get(0).getAccumulatedLoad()).isZero();

        // Kommt der Fuehler zurueck, wird die Luecke nicht nachgezaehlt
        runMinutes(1, 16, 60);
        assertThat(store.get(0).getAccumulatedLoad()).isCloseTo(0.73 / 60, within(0.001));
        assertThat(service.currentState().estimatePaused()).isFalse();
    }

    @Test
    void erreichtesKMeldetMaybeDryUndOeffnetFrage() {
        startDrying(60.0);
        runHours(24, 16, 60);      // 24 h * 0,726 = 17,4 < K 17,5
        assertThat(service.currentState().status()).isEqualTo("drying");

        runHours(1, 16, 60);

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
        assertThat(session.getMaybeDryAt()).isEqualTo(clock.now());
        assertThat(service.currentState().prompt()).isEqualTo("DRY");
        assertThat(session.getCurveCriterionAt()).isNull();
    }

    @Test
    void kurvenkriteriumGreiftNachHoechstwertUndHaltezeit() {
        startDrying(50.0);
        runMinutes(120, 16, 58);   // Mehrfeuchte 8 pp
        runMinutes(239, 16, 52);   // 2 pp = 25 % des Hoechstwerts, Start + 5:59
        assertThat(service.currentState().status()).isEqualTo("drying");

        runMinutes(1, 16, 52);     // Start + 6:00

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
        assertThat(session.getPeakExcess()).isEqualTo(8.0, within(1e-9));
        assertThat(session.getCurveCriterionAt()).isEqualTo(session.getStartedAt().plusHours(6));
        assertThat(session.getCurveCriterionLoad()).isEqualTo(session.getAccumulatedLoad());
        assertThat(session.getCurveCriterionLoad()).isLessThan(LaundryRules.DEFAULT_REQUIRED_LOAD);
    }

    @Test
    void kurvenkriteriumWartetDieHaltezeitAb() {
        startDrying(50.0);
        runMinutes(329, 16, 58);   // bis Start + 5:29 Mehrfeuchte 8 pp
        runMinutes(60, 16, 52);    // tief ab 5:30, Start + 6:29
        assertThat(service.currentState().status()).isEqualTo("drying");

        runMinutes(1, 16, 52);     // Start + 6:30: 60 min unter 25 %

        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
        assertThat(store.get(0).getCurveCriterionAt()).isEqualTo(store.get(0).getStartedAt().plusMinutes(390));
    }

    @Test
    void erneuterAnstiegSetztDieHaltezeitZurueck() {
        startDrying(50.0);
        runMinutes(329, 16, 58);   // bis 5:29
        runMinutes(30, 16, 52);    // tief 5:30 bis 5:59
        runMinutes(1, 16, 55);     // 6:00 wieder 5 pp ueber Grund
        runMinutes(60, 16, 52);    // tief ab 6:01, Start + 7:00
        assertThat(store.get(0).getCurveCriterionAt()).isNull();
        assertThat(service.currentState().status()).isEqualTo("drying");

        runMinutes(1, 16, 52);     // Start + 7:01: 60 min seit 6:01

        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
    }

    @Test
    void fuehlerpauseZaehltNichtAlsHaltezeit() {
        startDrying(50.0);
        runMinutes(329, 16, 58);   // bis 5:29
        runMinutes(10, 16, 52);    // tief 5:30 bis 5:39
        climate = Optional.empty();
        for (int i = 0; i < 50; i++) {   // 5:40 bis 6:29 keine Werte
            clock.advance(Duration.ofMinutes(1));
            service.tick();
        }
        runMinutes(60, 16, 52);    // tief ab 6:30, Start + 7:29
        assertThat(service.currentState().status()).isEqualTo("drying");

        runMinutes(1, 16, 52);     // Start + 7:30

        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
    }

    @Test
    void kurvenkriteriumGreiftNichtBeiKleinemAnstieg() {
        startDrying(50.0);
        runMinutes(60, 16, 52);    // Hoechstwert nur 2 pp
        runHours(8, 16, 50.2);

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.DRYING);
        assertThat(session.getCurveCriterionAt()).isNull();

        runHours(20, 16, 50.2);    // erst K beendet das Trocknen
        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
        assertThat(store.get(0).getCurveCriterionAt()).isNull();
    }

    @Test
    void maybeDryInDerRuhezeitWirdAufSiebenUhrVerschoben() {
        clock.set(LocalDateTime.of(2026, 11, 11, 17, 10));
        startDrying(50.0);
        runMinutes(120, 16, 58);
        runMinutes(240, 16, 52);   // Kriterium greift um 23:10

        LaundryDryingSession session = store.get(0);
        assertThat(clock.now()).isEqualTo(LocalDateTime.of(2026, 11, 11, 23, 10));
        assertThat(session.getCurveCriterionAt()).isEqualTo(clock.now());
        assertThat(service.currentState().status()).isEqualTo("drying");
        assertThat(service.currentState().prompt()).isEqualTo("NONE");

        runMinutes(469, 16, 52);   // 06:59
        assertThat(service.currentState().status()).isEqualTo("drying");

        runMinutes(1, 16, 52);     // 07:00
        assertThat(service.currentState().status()).isEqualTo("maybe_dry");
        assertThat(service.currentState().prompt()).isEqualTo("DRY");
        assertThat(store.get(0).getMaybeDryAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 7, 0));
    }

    @Test
    void nachtkarteNenntDasEndeDerRuhezeitStattJetzt() {
        clock.set(LocalDateTime.of(2026, 11, 11, 17, 10));
        startDrying(50.0);
        runMinutes(120, 16, 58);
        runMinutes(240, 16, 52);   // Kriterium greift um 23:10, Frage wartet bis 07:00

        assertThat(service.currentState().predictedDryAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 7, 0));
        runMinutes(5, 16, 52);
        assertThat(service.currentState().predictedDryAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 7, 0));
    }

    // ---------- Rueckmeldung ----------

    @Test
    void nochNichtFragtNachZweiStundenErneut() {
        reachMaybeDryByCurve();
        double load = store.get(0).getAccumulatedLoad();

        service.answerDry(false);

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
        assertThat(session.getLowerBoundLoad()).isEqualTo(load);
        assertThat(service.currentState().prompt()).isEqualTo("NONE");
        verify(auditService).record("laundry.answer.dry", "noch nicht");

        runMinutes(119, 16, 52);
        assertThat(service.currentState().prompt()).isEqualTo("NONE");
        runMinutes(1, 16, 52);
        assertThat(service.currentState().prompt()).isEqualTo("DRY");
        assertThat(service.currentState().status()).isEqualTo("maybe_dry");
    }

    @Test
    void jaTrockenSpeichertBestaetigteProbe() {
        reachMaybeDryByCurve();
        runMinutes(30, 16, 52);
        double load = store.get(0).getAccumulatedLoad();

        service.answerDry(true);

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.DONE);
        assertThat(session.getConfirmedLoad()).isEqualTo(load);
        assertThat(session.getEndedAt()).isEqualTo(clock.now());
        assertThat(service.currentState().status()).isEqualTo("idle");
        verify(auditService).record("laundry.answer.dry", "ja");
    }

    @Test
    void abgenommenVorMaybeDryIstKeineProbe() {
        startDrying(50.0);
        runHours(3, 16, 58);

        service.markRemoved();

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.DONE);
        assertThat(session.getConfirmedLoad()).isNull();
        verify(auditService).record(eq("laundry.removed"), any());
    }

    @Test
    void abgenommenNachMaybeDryZaehltAlsBestaetigt() {
        reachMaybeDryByCurve();
        double load = store.get(0).getAccumulatedLoad();

        service.markRemoved();

        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.DONE);
        assertThat(store.get(0).getConfirmedLoad()).isEqualTo(load);
    }

    @Test
    void nachZweiundsiebzigStundenEndetDerWaschgangMitSchwacherProbe() {
        reachMaybeDryByCurve();
        double criterionLoad = store.get(0).getCurveCriterionLoad();
        runHours(65, 16, 52);
        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);

        runHours(1, 16, 52);       // Start + 72 h

        LaundryDryingSession session = store.get(0);
        assertThat(session.getStatus()).isEqualTo(LaundrySessionStatus.EXPIRED);
        assertThat(session.getWeakLoad()).isEqualTo(criterionLoad);
        assertThat(session.getAccumulatedLoad()).isGreaterThan(criterionLoad);
        assertThat(service.currentState().status()).isEqualTo("idle");
    }

    @Test
    void antwortOhnePassendeFrageWirft() {
        assertThatThrownBy(() -> service.answerHung(true)).isInstanceOf(LaundryConflictException.class);
        assertThatThrownBy(() -> service.markRemoved()).isInstanceOf(LaundryConflictException.class);

        service.ask(LaundryTrigger.SWITCH_OFF, 58.9);
        assertThatThrownBy(() -> service.answerDry(true)).isInstanceOf(LaundryConflictException.class);
        assertThatThrownBy(() -> service.markRemoved()).isInstanceOf(LaundryConflictException.class);

        service.answerHung(true);
        assertThatThrownBy(() -> service.answerHung(true)).isInstanceOf(LaundryConflictException.class);
        assertThatThrownBy(() -> service.answerDry(true)).isInstanceOf(LaundryConflictException.class);
        verify(auditService, never()).record(eq("laundry.answer.dry"), any());
    }

    @Test
    void predictedDryAtNutztGelerntesK() {
        finishedSession(10.0, 1);
        finishedSession(12.0, 2);
        finishedSession(14.0, 3);
        startDrying(50.0);
        climate = Optional.of(new KellerClimate(16, 60, clock.now()));

        LaundryStateView view = service.currentState();

        double vpd = DryingPhysics.vaporPressureDeficitKpa(16, 60);
        assertThat(view.requiredLoad()).isEqualTo(12.0, within(1e-9));
        long expectedSeconds = Math.round(12.0 / vpd * 3600);
        assertThat(Duration.between(clock.now(), view.predictedDryAt()).toSeconds())
                .isCloseTo(expectedSeconds, within(1L));
    }

    @Test
    void predictedDryAtEndetSpaetestensMitDemWaschgang() {
        startDrying(50.0);
        climate = Optional.of(new KellerClimate(5, 95, clock.now()));   // kaum Trockenleistung

        LaundryStateView view = service.currentState();

        assertThat(view.predictedDryAt()).isEqualTo(store.get(0).getStartedAt().plus(LaundryRules.SESSION_TIMEOUT));
    }

    // ---------- Hilfen ----------

    private void startDrying(double baseline) {
        service.ask(LaundryTrigger.SWITCH_OFF, baseline);
        service.answerHung(true);
    }

    /** Start 10:00, Mehrfeuchte 8 pp, dann 2 pp — Kurvenkriterium um 16:00. */
    private void reachMaybeDryByCurve() {
        startDrying(50.0);
        runMinutes(120, 16, 58);
        runMinutes(240, 16, 52);
        assertThat(store.get(0).getStatus()).isEqualTo(LaundrySessionStatus.MAYBE_DRY);
    }

    private void runMinutes(int minutes, double tempC, double humidity) {
        for (int i = 0; i < minutes; i++) {
            clock.advance(Duration.ofMinutes(1));
            climate = Optional.of(new KellerClimate(tempC, humidity, clock.now()));
            service.tick();
        }
    }

    private void runHours(int hours, double tempC, double humidity) {
        for (int i = 0; i < hours; i++) {
            clock.advance(Duration.ofHours(1));
            climate = Optional.of(new KellerClimate(tempC, humidity, clock.now()));
            service.tick();
        }
    }

    @Test
    void verfalleneFrageOhneStartFliesstNichtInsLernenEin() {
        for (int i = 0; i < 10; i++) {
            LaundryDryingSession asked = new LaundryDryingSession();
            asked.setStatus(LaundrySessionStatus.EXPIRED);
            asked.setTrigger(LaundryTrigger.SWITCH_OFF);
            asked.setAskedAt(clock.now().minusHours(i + 1));
            store.add(asked);
        }
        finishedSession(12.0, 5);
        finishedSession(12.0, 6);
        finishedSession(12.0, 7);

        assertThat(service.recentSessions()).hasSize(3);
        assertThat(service.requiredLoad()).isEqualTo(12.0);
    }

    private void finishedSession(double confirmedLoad, int daysAgo) {
        LaundryDryingSession session = new LaundryDryingSession();
        session.setStatus(LaundrySessionStatus.DONE);
        session.setTrigger(LaundryTrigger.SWITCH_OFF);
        session.setAskedAt(clock.now().minusDays(daysAgo));
        session.setStartedAt(clock.now().minusDays(daysAgo));
        session.setConfirmedLoad(confirmedLoad);
        session.setEndedAt(clock.now().minusDays(daysAgo).plusHours(20));
        store.add(session);
        session.setId((long) store.size());
    }

    @SuppressWarnings("unchecked")
    private LaundryDryingSessionRepository fakeRepository() {
        LaundryDryingSessionRepository repository = mock(LaundryDryingSessionRepository.class);
        when(repository.save(any(LaundryDryingSession.class))).thenAnswer(inv -> {
            LaundryDryingSession session = inv.getArgument(0);
            if (session.getId() == null) {
                store.add(session);
                session.setId((long) store.size());
            }
            return session;
        });
        when(repository.findFirstByStatusInOrderByAskedAtDesc(anyCollection())).thenAnswer(inv ->
                newestFirst((Collection<LaundrySessionStatus>) inv.getArgument(0)).stream().findFirst());
        when(repository.findTop10ByStatusInAndStartedAtIsNotNullOrderByAskedAtDesc(anyCollection())).thenAnswer(inv ->
                newestFirst((Collection<LaundrySessionStatus>) inv.getArgument(0)).stream()
                        .filter(s -> s.getStartedAt() != null).limit(10).toList());
        when(repository.findFirstByOrderByAskedAtDesc()).thenAnswer(inv ->
                newestFirst(List.of(LaundrySessionStatus.values())).stream().findFirst());
        return repository;
    }

    private List<LaundryDryingSession> newestFirst(Collection<LaundrySessionStatus> statuses) {
        return store.stream()
                .filter(s -> statuses.contains(s.getStatus()))
                .sorted(Comparator.comparing(LaundryDryingSession::getAskedAt).reversed())
                .toList();
    }

    /** Verstellbare Uhr in Berliner Zeit. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(LocalDateTime start) {
            set(start);
        }

        void set(LocalDateTime time) {
            instant = time.atZone(BERLIN).toInstant();
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        LocalDateTime now() {
            return LocalDateTime.ofInstant(instant, BERLIN);
        }

        @Override
        public ZoneId getZone() {
            return BERLIN;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
