package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresenceEvaluatorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");

    @Mock
    private PresenceSettingsService settings;

    private PresenceMonitor monitor;
    private PresenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor(Clock.fixed(START, ZoneId.of("Europe/Berlin")));
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
    void stilleJenseitsDerKarenzIstAbwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(11 * 60);

        PresenceEvaluator.PersonPresence result = evaluator.evaluate(List.of(device(1, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.AWAY);
        assertThat(result.lastSeenAt()).isEqualTo(seen);
    }

    @Test
    void nieGesehenWaehrendDerAnlaufKarenzIstUnbekannt() {
        // Kein Update seit Start: die Entitaet soll ihren DB-Wert behalten (nie raten).
        Instant now = START.plusSeconds(5 * 60);
        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.UNKNOWN);
    }

    @Test
    void nieGesehenNachDerAnlaufKarenzIstAbwesend() {
        Instant now = START.plusSeconds(11 * 60);
        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.AWAY);
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
}
