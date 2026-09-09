package com.household.manager.mode;

import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeQuickAccessResolverTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private ModeQuickAccessRepository repository;

    /** Feste Uhr auf der gewuenschten lokalen Uhrzeit des 9. September 2026. */
    private ModeQuickAccessResolver resolverAt(String localTime) {
        Instant instant = java.time.LocalDate.of(2026, 9, 9)
                .atTime(LocalTime.parse(localTime))
                .atZone(BERLIN)
                .toInstant();
        return new ModeQuickAccessResolver(repository, Clock.fixed(instant, BERLIN));
    }

    private ModeQuickAccess window(String from, String to, boolean active) {
        return ModeQuickAccess.builder()
                .id(1L)
                .entityId(NACHTMODUS)
                .fromTime(LocalTime.parse(from))
                .toTime(LocalTime.parse(to))
                .active(active)
                .build();
    }

    @Test
    void meldetEinenModusInnerhalbEinesTagesfensters() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("12:00").dueEntityIds()).containsExactly(NACHTMODUS);
    }

    @Test
    void meldetNichtsVorUndNachEinemTagesfenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("07:59").dueEntityIds()).isEmpty();
        assertThat(resolverAt("18:00").dueEntityIds()).isEmpty();
    }

    /** Der Beginn gehoert zum Fenster, das Ende nicht — halboffenes Intervall [from, to). */
    @Test
    void behandeltDenBeginnInklusiveUndDasEndeExklusiv() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("08:00").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("17:59").dueEntityIds()).containsExactly(NACHTMODUS);
    }

    /** 20:00-06:00 ueberspannt Mitternacht: abends und frueh morgens faellig, mittags nicht. */
    @Test
    void beherrschtEinFensterUeberMitternacht() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("20:00", "06:00", true)));

        assertThat(resolverAt("20:00").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("23:59").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("05:59").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("06:00").dueEntityIds()).isEmpty();
        assertThat(resolverAt("13:00").dueEntityIds()).isEmpty();
    }

    /**
     * Ein Fenster mit gleichem Beginn und Ende ist leer, nicht "immer". Die API laesst so
     * etwas nicht entstehen; eine von Hand eingetragene Zeile darf trotzdem keinen
     * Dauerknopf erzeugen.
     */
    @Test
    void behandeltGleichenBeginnUndEndeAlsLeeresFenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "08:00", true)));

        assertThat(resolverAt("08:00").dueEntityIds()).isEmpty();
    }

    @Test
    void ignoriertDeaktivierteFenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of());

        assertThat(resolverAt("12:00").dueEntityIds()).isEmpty();
    }

    /**
     * Wirft nie: der Resolver reichert nur die Modus-Antwort an. Ein Datenbankfehler darf
     * nicht die gesamte Modus-Leiste des Wandtablets in einen 500 kippen.
     */
    @Test
    void meldetBeiEinemDatenbankfehlerNichtsStattZuWerfen() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("DB weg"));

        Set<String> due = resolverAt("12:00").dueEntityIds();

        assertThat(due).isEmpty();
    }
}
