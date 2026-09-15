package com.household.manager.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.EntityStateRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeQuickAccessResolverTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";
    private static final String ABWESEND = "input_boolean.manual_abwesend";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final String KAMIN = "input_boolean.manual_kamin";

    @Mock
    private ModeQuickAccessRepository repository;
    @Mock
    private EntityStateRepository entityStateRepository;

    /** Feste Uhr auf der gewuenschten lokalen Uhrzeit des 9. September 2026. */
    private ModeQuickAccessResolver resolverAt(String localTime) {
        Instant instant = java.time.LocalDate.of(2026, 9, 9)
                .atTime(LocalTime.parse(localTime))
                .atZone(BERLIN)
                .toInstant();
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        return new ModeQuickAccessResolver(repository, entityStateRepository,
                new ModeResponseMapper(entityMapper), Clock.fixed(instant, BERLIN));
    }

    private static EntityState helper(String entityId, String name, String state, String attributes) {
        return EntityState.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(entityId.substring("input_boolean.manual_".length()))
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }

    private ModeQuickAccess window(String from, String to, boolean active) {
        return window(NACHTMODUS, from, to, active);
    }

    private ModeQuickAccess window(String entityId, String from, String to, boolean active) {
        return ModeQuickAccess.builder()
                .id(1L)
                .entityId(entityId)
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

    /**
     * Ueberprueft nur den leeren Rueckgabewert der Query, nicht die Deaktivierung selbst:
     * die Filterung auf aktive Zeilen steckt in {@code findByActiveTrue()}, der Resolver
     * wertet {@code active} bewusst nirgends aus.
     */
    @Test
    void meldetNichtsBeiLeererFensterliste() {
        when(repository.findByActiveTrue()).thenReturn(List.of());

        assertThat(resolverAt("12:00").dueEntityIds()).isEmpty();
    }

    /** {@code dueEntityIds()} liefert ein Set — mehrere gleichzeitig offene Fenster muessen alle auftauchen. */
    @Test
    void meldetMehrereGleichzeitigFaelligeFenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of(
                window(NACHTMODUS, "08:00", "18:00", true),
                window(ABWESEND, "08:00", "18:00", true)
        ));

        assertThat(resolverAt("12:00").dueEntityIds()).containsExactlyInAnyOrder(NACHTMODUS, ABWESEND);
    }

    /**
     * Wirft nie. Ein Datenbankfehler darf nicht das Wandtablet in einen 500 kippen.
     */
    @Test
    void meldetBeiEinemDatenbankfehlerNichtsStattZuWerfen() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("DB weg"));

        Set<String> due = resolverAt("12:00").dueEntityIds();

        assertThat(due).isEmpty();
        assertThat(resolverAt("12:00").dueEntities()).isEmpty();
    }

    /**
     * {@code dueEntities()} liefert die faelligen Helfer fertig fuer das Dashboard — Modi und
     * gewoehnliche Helfer gleichermassen, mit ihrem echten Zustand. Ein Fenster fuer eine
     * geloeschte Entity faellt still weg.
     */
    @Test
    void liefertDieFaelligenHelferMitZustandUndUeberspringtGeloeschte() {
        when(repository.findByActiveTrue()).thenReturn(List.of(
                window(NACHTMODUS, "08:00", "18:00", true),
                window(KAMIN, "08:00", "18:00", true),
                window("input_boolean.manual_geloescht", "08:00", "18:00", true),
                window(ABWESEND, "20:00", "22:00", true)));
        when(entityStateRepository.findByDomainAndSourceOrderByEntityIdAsc(
                EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)).thenReturn(List.of(
                helper(ABWESEND, "Abwesend", "off", "{\"icon\":\"exit_to_app\",\"mode\":true}"),
                helper(KAMIN, "Kamin", "on", "{\"icon\":\"fireplace\"}"),
                helper(NACHTMODUS, "Nachtmodus", "off", "{\"icon\":\"nights_stay\",\"mode\":true}")));

        List<ModeResponse> due = resolverAt("12:00").dueEntities();

        assertThat(due).extracting(ModeResponse::entityId).containsExactly(KAMIN, NACHTMODUS);
        assertThat(due.get(0).state()).isEqualTo("on");
        assertThat(due.get(0).icon()).isEqualTo("fireplace");
    }

    /** Ein Eintrag ohne Zeitfenster (beide Zeiten null) gilt zu jeder Uhrzeit. */
    @Test
    void meldetEinenEintragOhneFensterImmer() {
        ModeQuickAccess immer = ModeQuickAccess.builder().id(3L).entityId(KAMIN).active(true).build();
        when(repository.findByActiveTrue()).thenReturn(List.of(immer));

        assertThat(resolverAt("00:00").dueEntityIds()).containsExactly(KAMIN);
        assertThat(resolverAt("12:34").dueEntityIds()).containsExactly(KAMIN);
        assertThat(resolverAt("23:59").dueEntityIds()).containsExactly(KAMIN);
    }

    /**
     * Eine halb gesetzte Zeile (nur Beginn oder nur Ende) entsteht ueber die API nie, per Hand
     * in der DB aber schon. Sie gilt fail-safe als "nie" — ein Tippfehler darf keinen
     * Dauerknopf erzeugen.
     */
    @Test
    void wertetEineHalbGesetzteZeileAlsNie() {
        ModeQuickAccess nurBeginn = ModeQuickAccess.builder().id(4L).entityId(KAMIN)
                .fromTime(LocalTime.of(8, 0)).active(true).build();
        ModeQuickAccess nurEnde = ModeQuickAccess.builder().id(5L).entityId(NACHTMODUS)
                .toTime(LocalTime.of(18, 0)).active(true).build();
        when(repository.findByActiveTrue()).thenReturn(List.of(nurBeginn, nurEnde));

        assertThat(resolverAt("12:00").dueEntityIds()).isEmpty();
    }

    /** Ohne offenes Fenster wird die Entity-Tabelle gar nicht erst befragt. */
    @Test
    void fragtOhneFaelligesFensterKeineEntitiesAb() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("19:00").dueEntities()).isEmpty();
        verify(entityStateRepository, never()).findByDomainAndSourceOrderByEntityIdAsc(any(), any());
    }
}
