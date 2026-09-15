package com.household.manager.mode;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeQuickAccessServiceTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";
    private static final String KAMIN = "input_boolean.manual_kamin";

    @Mock
    private ModeQuickAccessRepository repository;
    @Mock
    private HouseModeQueryService houseModeQueryService;
    @Mock
    private AuditService auditService;

    private ModeQuickAccessService service;

    @BeforeEach
    void setUp() {
        service = new ModeQuickAccessService(repository, houseModeQueryService, auditService);
        // Die Modus-Leiste: ein Haus-Modus und ein per Sichtbarkeitsregel hinzugeholter Helfer.
        lenient().when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId(NACHTMODUS).displayName("Nachtmodus")
                        .icon("nights_stay").state("off").build(),
                ModeResponse.builder().entityId(KAMIN).displayName("Kamin")
                        .icon("fireplace").state("off").build()));
    }

    private ModeQuickAccessDtos.Request request(String entityId, String from, String to) {
        return new ModeQuickAccessDtos.Request(entityId, LocalTime.parse(from), LocalTime.parse(to), true);
    }

    private ModeQuickAccess saved() {
        return ModeQuickAccess.builder().id(7L).entityId(NACHTMODUS)
                .fromTime(LocalTime.of(20, 0)).toTime(LocalTime.of(6, 0)).active(true).build();
    }

    @Test
    void legtEinFensterAnUndAuditiertEs() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(saved());

        ModeQuickAccessDtos.Response response = service.create(request(NACHTMODUS, "20:00", "06:00"));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.displayName()).isEqualTo("Nachtmodus");
        verify(auditService).record("mode.quick-access.create", "Nachtmodus 20:00-06:00");
    }

    /**
     * Seit 2026-09-15 darf jeder Eintrag der Modus-Leiste ein Fenster bekommen, auch ein
     * hinzugeholter gewoehnlicher Helfer — die Flexibilitaet war ausdruecklich gewuenscht.
     */
    @Test
    void akzeptiertEinenHinzugeholtenHelferAusDerLeiste() {
        when(repository.findByEntityId(KAMIN)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Response response = service.create(request(KAMIN, "17:00", "22:00"));

        assertThat(response.displayName()).isEqualTo("Kamin");
        verify(auditService).record("mode.quick-access.create", "Kamin 17:00-22:00");
    }

    /**
     * Der Schnellzugriff ist eine Teilmenge der Modus-Leiste: was dort nicht steht (ein
     * Schalter, oder ein Helfer ohne ALWAYS-Regel), bekommt kein Fenster. Das Dropdown der
     * Admin-Seite bietet nur Leisten-Mitglieder an; ueber die API kaeme sonst Unsinn durch.
     */
    @Test
    void lehntEineEntityAbDieNichtInDerModusLeisteSteht() {
        assertThatThrownBy(() -> service.create(request("switch.meross_kaffeemaschine", "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht in der Modus-Leiste");
        assertThatThrownBy(() -> service.create(request("input_boolean.manual_urlaub", "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht in der Modus-Leiste");
        verify(repository, never()).save(any());
    }

    /**
     * Gleicher Beginn und gleiches Ende sind mehrdeutig ("nie" oder "immer"?) und werden
     * deshalb an der API-Grenze abgelehnt statt im Resolver geraten.
     */
    @Test
    void lehntGleichenBeginnUndEndeAb() {
        assertThatThrownBy(() -> service.create(request(NACHTMODUS, "20:00", "20:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
        verify(repository, never()).save(any());
    }

    @Test
    void lehntEinenFehlendenHelferAb() {
        assertThatThrownBy(() -> service.create(request(null, "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Helfer");
    }

    @Test
    void lehntEinHalbGesetztesFensterAb() {
        ModeQuickAccessDtos.Request ohneEnde =
                new ModeQuickAccessDtos.Request(NACHTMODUS, LocalTime.of(20, 0), null, true);
        ModeQuickAccessDtos.Request ohneBeginn =
                new ModeQuickAccessDtos.Request(NACHTMODUS, null, LocalTime.of(6, 0), true);

        assertThatThrownBy(() -> service.create(ohneEnde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
        assertThatThrownBy(() -> service.create(ohneBeginn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
        verify(repository, never()).save(any());
    }

    /**
     * Seit 2026-09-15 darf ein Eintrag OHNE Zeitfenster stehen — der Helfer ist dann immer im
     * Schnellzugriff. Beide Zeiten leer ist die einzige gueltige Form dafuer.
     */
    @Test
    void legtEinenEintragOhneZeitfensterAlsImmerAn() {
        when(repository.findByEntityId(KAMIN)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Response response =
                service.create(new ModeQuickAccessDtos.Request(KAMIN, null, null, true));

        assertThat(response.fromTime()).isNull();
        assertThat(response.toTime()).isNull();
        verify(auditService).record("mode.quick-access.create", "Kamin immer");
    }

    /** Ein Modus hat hoechstens ein Fenster — sonst waere unklar, welches gilt. */
    @Test
    void lehntEinZweitesFensterFuerDenselbenModusAb() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.of(saved()));

        assertThatThrownBy(() -> service.create(request(NACHTMODUS, "08:00", "10:00")))
                .isInstanceOf(DuplicateEntityException.class);
        verify(repository, never()).save(any());
    }

    /** Beim Aendern darf die eigene Zeile nicht als Duplikat gelten. */
    @Test
    void aendertEinFensterUndErlaubtDabeiDieEigeneZeile() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.of(saved()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Response response = service.update(7L, request(NACHTMODUS, "21:00", "07:00"));

        assertThat(response.fromTime()).isEqualTo(LocalTime.of(21, 0));
        verify(auditService).record("mode.quick-access.update", "Nachtmodus 21:00-07:00");
    }

    /**
     * Eine fremde Zeile mit demselben Modus muss beim Aendern weiterhin als Duplikat gelten —
     * nur die eigene Zeile ist von der Pruefung ausgenommen. Dieser Fall wird eigenstaendig
     * geprueft, weil ein invertierter oder gestrichener Id-Filter im Produktionscode sonst
     * unbemerkt bliebe.
     */
    @Test
    void lehntEineFremdeZeileAlsDuplikatBeimAendernAb() {
        ModeQuickAccess fremdeZeile = ModeQuickAccess.builder().id(5L).entityId(NACHTMODUS)
                .fromTime(LocalTime.of(8, 0)).toTime(LocalTime.of(10, 0)).active(true).build();
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.of(fremdeZeile));

        assertThatThrownBy(() -> service.update(7L, request(NACHTMODUS, "21:00", "07:00")))
                .isInstanceOf(DuplicateEntityException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void meldetEineUnbekannteIdAlsNichtGefunden() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request(NACHTMODUS, "20:00", "06:00")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void loeschtEinFensterUndAuditiertEs() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));

        service.delete(7L);

        verify(repository).delete(any());
        verify(auditService).record("mode.quick-access.delete", "Nachtmodus 20:00-06:00");
    }

    /**
     * Ein Fenster fuer einen Helfer, den es nicht mehr gibt, bleibt in der Liste sichtbar —
     * ohne Anzeigenamen. Wuerde es weggefiltert, waere es nicht mehr loeschbar.
     */
    @Test
    void listetEinFensterOhneZugehoerigenHelferOhneAnzeigenamen() {
        ModeQuickAccess verwaist = ModeQuickAccess.builder().id(8L).entityId("input_boolean.manual_weg")
                .fromTime(LocalTime.of(1, 0)).toTime(LocalTime.of(2, 0)).active(true).build();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(verwaist));

        List<ModeQuickAccessDtos.Response> list = service.list();

        assertThat(list).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.entityId()).isEqualTo("input_boolean.manual_weg");
                    assertThat(entry.displayName()).isNull();
                });
    }

    /** Ein fehlendes active-Feld heisst "aktiv" — wie der Default der Spalte. */
    @Test
    void behandeltEinFehlendesAktivFeldAlsAktiv() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Request ohneAktiv =
                new ModeQuickAccessDtos.Request(NACHTMODUS, LocalTime.of(20, 0), LocalTime.of(6, 0), null);

        assertThat(service.create(ohneAktiv).active()).isTrue();
    }
}
