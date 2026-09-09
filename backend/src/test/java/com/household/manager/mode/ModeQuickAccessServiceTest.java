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
        lenient().when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId(NACHTMODUS).displayName("Nachtmodus")
                        .icon("nights_stay").state("off").quickAccess(false).build()));
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

    /** Das Dropdown der Admin-Seite kennt nur echte Modi; ueber die API kaeme sonst Unsinn durch. */
    @Test
    void lehntEineEntityAbDieKeinHausModusIst() {
        assertThatThrownBy(() -> service.create(request("switch.meross_kaffeemaschine", "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kein Haus-Modus");
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
    void lehntEinenFehlendenModusAb() {
        assertThatThrownBy(() -> service.create(request(null, "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Modus");
    }

    @Test
    void lehntFehlendeZeitenAb() {
        ModeQuickAccessDtos.Request ohneEnde =
                new ModeQuickAccessDtos.Request(NACHTMODUS, LocalTime.of(20, 0), null, true);

        assertThatThrownBy(() -> service.create(ohneEnde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
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
     * Ein Fenster fuer einen Modus, den es nicht mehr gibt, bleibt in der Liste sichtbar —
     * ohne Anzeigenamen. Wuerde es weggefiltert, waere es nicht mehr loeschbar.
     */
    @Test
    void listetEinFensterOhneZugehoerigenModusOhneAnzeigenamen() {
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
