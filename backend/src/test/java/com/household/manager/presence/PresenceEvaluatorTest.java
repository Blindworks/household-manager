package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PresenceEvaluatorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");

    @Mock
    private PresenceSettingsService settings;

    private PresenceMonitor monitor;
    private PresenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        evaluator = new PresenceEvaluator(monitor, settings);
        lenient().when(settings.getAwayGraceMinutes()).thenReturn(10L);
    }

    private PresenceDevice device(long id, boolean active) {
        return PresenceDevice.builder().id(id).userId(5L).name("iPhone")
                .host("192.168.1.50").active(active).build();
    }

    @Test
    void antwortMachtSofortAnwesend() {
        Instant now = START.plusSeconds(600);
        monitor.update(1L, true, now);

        PresenceEvaluator.PersonPresence result = evaluator.evaluate(List.of(device(1, true)), now);

        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.PRESENT);
        assertThat(result.lastSeenAt()).isEqualTo(now);
    }

    @Test
    void stilleInnerhalbDerKarenzBleibtAnwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(9 * 60);

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void stilleGenauAufDerKarenzgrenzeIstNochAnwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plus(Duration.ofMinutes(10)); // exakt grace

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void stilleJenseitsDerKarenzIstAbwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(11 * 60);

        PresenceEvaluator.PersonPresence result = evaluator.evaluate(List.of(device(1, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.AWAY);
        assertThat(result.lastSeenAt()).isEqualTo(seen);
    }

    @Test
    void abweichendeKarenzzeitWirdBeachtet() {
        // Mit dem Default (10 min) waere das AWAY - mit 30 min noch PRESENT.
        lenient().when(settings.getAwayGraceMinutes()).thenReturn(30L);
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(20 * 60);

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void nieGesehenWaehrendDerAnlaufKarenzIstUnbekannt() {
        // Kein Update seit Anlegen des Geraets (kein Monitor-Eintrag): die Entitaet
        // soll ihren DB-Wert behalten (nie raten).
        Instant now = START.plusSeconds(5 * 60);
        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.UNKNOWN);
    }

    @Test
    void nieGesehenNachDerAnlaufKarenzIstAbwesend() {
        monitor.update(1L, false, START); // erste Pruefung, keine Antwort
        Instant now = START.plusSeconds(11 * 60);

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void probezeitGenauAufDerGrenzeIstSchonAbwesend() {
        monitor.update(1L, false, START); // erste Pruefung, keine Antwort
        Instant now = START.plus(Duration.ofMinutes(10)); // exakt grace seit firstCheckedAt

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void neuesGeraetInEigenerProbezeitBleibtUnbekanntTrotzLangeUeberfaelligemZweitgeraet() {
        // Prozess laeuft seit Stunden, Geraet 2 ist laengst durch seine eigene
        // Probezeit (erste Pruefung liegt drei Stunden zurueck und war schon
        // damals still - fuer sich allein waere es AWAY), Geraet 1 wurde gerade
        // erst hinzugefuegt und zum ersten Mal (still) geprueft. Die Probezeit
        // gilt PRO Geraet, nicht ab einem Prozess-Start: das laengst ueberfaellige
        // Geraet 2 darf das frische Geraet 1 nicht "mitziehen".
        Instant now = START.plusSeconds(3 * 60 * 60);
        monitor.update(2L, false, START);
        monitor.update(1L, false, now);

        PresenceEvaluator.PersonPresence result =
                evaluator.evaluate(List.of(device(1, true), device(2, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.UNKNOWN);
    }

    @Test
    void nurDeaktivierteGeraeteIstUnavailable() {
        assertThat(evaluator.evaluate(List.of(device(1, false)), START.plusSeconds(60)).state())
                .isEqualTo(PresenceEvaluator.PersonState.UNAVAILABLE);
    }

    @Test
    void lastSeenEinesDeaktiviertenGeraetsZaehltNicht() {
        Instant now = START.plusSeconds(20 * 60);
        monitor.update(1L, true, now);          // deaktiviertes Geraet, frisch gesehen
        monitor.update(2L, true, START.plusSeconds(60)); // aktives Geraet, lange still

        PresenceEvaluator.PersonPresence result =
                evaluator.evaluate(List.of(device(1, false), device(2, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void zweitgeraetHaeltAnwesend() {
        Instant now = START.plusSeconds(20 * 60);
        monitor.update(1L, true, START.plusSeconds(60)); // lange still
        monitor.update(2L, true, now);                   // frisch

        assertThat(evaluator.evaluate(List.of(device(1, true), device(2, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void aggregatRegeln() {
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.PRESENT, PresenceEvaluator.PersonState.AWAY)))
                .contains("on");
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.AWAY, PresenceEvaluator.PersonState.AWAY)))
                .contains("off");
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.UNAVAILABLE, PresenceEvaluator.PersonState.UNAVAILABLE)))
                .contains("unavailable");
        // Mischung ohne PRESENT: keine Aussage, nichts melden
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.AWAY, PresenceEvaluator.PersonState.UNKNOWN)))
                .isEmpty();
        assertThat(evaluator.aggregateState(List.of())).isEmpty();
    }

    @Test
    void entityStateBildetAlleZustaendeAb() {
        assertThat(PresenceEvaluator.entityState(PresenceEvaluator.PersonState.PRESENT)).isEqualTo("on");
        assertThat(PresenceEvaluator.entityState(PresenceEvaluator.PersonState.AWAY)).isEqualTo("off");
        assertThat(PresenceEvaluator.entityState(PresenceEvaluator.PersonState.UNAVAILABLE)).isEqualTo("unavailable");
        assertThat(PresenceEvaluator.entityState(PresenceEvaluator.PersonState.UNKNOWN)).isEqualTo("unknown");
    }
}
