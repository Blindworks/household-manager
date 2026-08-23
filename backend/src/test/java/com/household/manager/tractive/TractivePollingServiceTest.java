package com.household.manager.tractive;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.TractiveEntityMapper;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import com.household.manager.tractive.dto.TractiveTrackableRefDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractivePollingServiceTest {

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthService authService;
    @Mock
    private TractiveEntityMapper mapper;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private TractivePositionRecorder positionRecorder;

    private TractiveProperties properties;
    private TractivePollingService service;

    private static final EntityStateUpdate LOCATION_UPDATE = EntityStateUpdate.builder()
            .entityId("sensor.tractive_dev_9_location")
            .domain(EntityDomain.SENSOR)
            .source(EntitySource.TRACTIVE)
            .sourceRef("dev-9")
            .friendlyName("Bello")
            .state("Garten")
            .attributes(Map.of())
            .build();

    private static final EntityStateUpdate HOME_UPDATE = EntityStateUpdate.builder()
            .entityId("binary_sensor.tractive_dev_9_home")
            .domain(EntityDomain.BINARY_SENSOR)
            .source(EntitySource.TRACTIVE)
            .sourceRef("dev-9")
            .friendlyName("Bello zu Hause")
            .state("on")
            .attributes(Map.of())
            .build();

    @BeforeEach
    void setUp() {
        properties = new TractiveProperties();
        service = new TractivePollingService(properties, apiClient, authService, mapper,
                entityStateService, positionRecorder);
    }

    private void givenAuthenticated() {
        when(authService.getValidToken()).thenReturn(Optional.of(TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken("tok")
                .userId("u-1")
                .email("halter@example.com")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .updatedAt(LocalDateTime.now())
                .build()));
    }

    private void givenOnePet() {
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenReturn(List.of(new TractiveTrackableRefDto("trk-1")));
        when(apiClient.getTrackable("tok", "u-1", "trk-1"))
                .thenReturn(new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")));
        when(apiClient.getPosition("tok", "u-1", "dev-9"))
                .thenReturn(new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L));
        when(apiClient.getHardware("tok", "u-1", "dev-9"))
                .thenReturn(new TractiveHardwareDto(87, "NOT_CHARGING"));
        when(apiClient.listGeofences("tok", "u-1", "dev-9")).thenReturn(List.of());
    }

    @Test
    void pollReportsMappedStates() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        service.poll();

        verify(entityStateService).reportState(LOCATION_UPDATE);
    }

    /** Frisch installiert, nie angemeldet: es duerfen keine Phantom-Entitaeten entstehen. */
    @Test
    void withoutAnyLoginNothingIsReported() {
        when(authService.getValidToken()).thenReturn(Optional.empty());

        service.poll();

        verifyNoInteractions(apiClient, entityStateService);
    }

    /** Nach Ablauf des Tokens gelten die zuletzt gemeldeten Entitaeten als nicht mehr bekannt. */
    @Test
    void expiredTokenMarksPreviouslyReportedEntitiesUnavailable() {
        // 1. Zyklus: erfolgreich, fuellt den Cache
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));
        service.poll();
        reset(entityStateService);

        // 2. Zyklus: Token abgelaufen
        when(authService.getValidToken()).thenReturn(Optional.empty());
        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .allMatch(update -> "unavailable".equals(update.state())));
        assertTrue(captor.getAllValues().stream()
                .anyMatch(update -> update.entityId().contains("location")));
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(apiClient, authService, entityStateService);
    }

    @Test
    void cloudFailureMarksLastKnownEntitiesUnavailable() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        service.poll();
        reset(apiClient);
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));

        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate unavailable = captor.getAllValues().get(1);
        assertEquals("sensor.tractive_dev_9_location", unavailable.entityId());
        assertEquals("unavailable", unavailable.state());
    }

    /**
     * Die Haustier-API muss denselben Zeitpunkt sehen wie der letzte erfolgreiche Poll –
     * ein Ausfall darf lastPolledAt nicht veraendern, sonst wuerde ein eingefrorener
     * Snapshot mit einem neuen Zeitpunkt neu bewertet.
     */
    @Test
    void lastPolledAtIsSetOnSuccessAndUnchangedAfterAFailure() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        service.poll();
        Instant firstPolledAt = service.lastPolledAt();
        assertNotNull(firstPolledAt);

        reset(apiClient);
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));
        assertDoesNotThrow(() -> service.poll());

        assertEquals(firstPolledAt, service.lastPolledAt());
    }

    @Test
    void oneBrokenPetDoesNotAbortTheCycle() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1")).thenReturn(List.of(
                new TractiveTrackableRefDto("broken"), new TractiveTrackableRefDto("trk-1")));
        when(apiClient.getTrackable("tok", "u-1", "broken"))
                .thenThrow(new TractiveException("boom"));
        when(apiClient.getTrackable("tok", "u-1", "trk-1"))
                .thenReturn(new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")));
        when(apiClient.getPosition("tok", "u-1", "dev-9"))
                .thenReturn(new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L));
        when(apiClient.getHardware("tok", "u-1", "dev-9"))
                .thenReturn(new TractiveHardwareDto(87, "NOT_CHARGING"));
        when(apiClient.listGeofences("tok", "u-1", "dev-9")).thenReturn(List.of());
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        assertDoesNotThrow(() -> service.poll());

        verify(entityStateService).reportState(LOCATION_UPDATE);
    }

    @Test
    void refreshNowPollsImmediately() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        service.refreshNow();

        verify(entityStateService).reportState(LOCATION_UPDATE);
        assertNotNull(service.lastPolledAt());
    }

    /**
     * Ohne Tractive-Anmeldung darf KEINE TractiveAuthException nach aussen: sie wuerde als
     * 401 beim Frontend landen, und der Auth-Interceptor wirft den Nutzer daraufhin aus der
     * Haushalts-Session, obwohl nur die Tractive-Anmeldung fehlt.
     */
    @Test
    void refreshNowReportsAMissingLoginAsIllegalStateNotAsAuthFailure() {
        when(authService.getValidToken()).thenReturn(Optional.empty());

        // IllegalStateException ist keine TractiveException — der Handler dafuer liefert 400,
        // nicht 401, und genau das haelt dieser Test fest.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.refreshNow());

        assertTrue(ex.getMessage().contains("Tractive-Anmeldung"));
    }

    /**
     * Der reale Fall: jeder Tier-Abruf scheitert (hier am Rate-Limit), die Cloud antwortet aber.
     * Vorher galt das als Erfolg mit null Tieren — die Seite behauptete daraufhin, das Konto
     * habe keinen Tracker. Der Grund muss den Aufrufer erreichen.
     */
    @Test
    void refreshNowReportsWhyNoPetCouldBeRead() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenReturn(List.of(new TractiveTrackableRefDto("trk-1")));
        when(apiClient.getTrackable("tok", "u-1", "trk-1"))
                .thenThrow(new TractiveException("Tractive-Abruf /trackable_object/trk-1 fehlgeschlagen: 500"));

        TractiveException ex = assertThrows(TractiveException.class, () -> service.refreshNow());

        assertTrue(ex.getMessage().contains("1 Objekt"));
        assertTrue(ex.getMessage().contains("trk-1"));
    }

    /** Ein leeres Konto ist eine andere Aussage als "alle Abrufe gescheitert". */
    @Test
    void refreshNowSaysSoWhenTheAccountListIsEmpty() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1")).thenReturn(List.of());

        TractiveException ex = assertThrows(TractiveException.class, () -> service.refreshNow());

        assertTrue(ex.getMessage().contains("leere Liste"));
    }

    /**
     * Ein Rate-Limit muss seinen Typ behalten (429 statt 502) und weitere Abrufe sperren –
     * Nachdruecken bei ausbleibenden Daten wuerde das Limit sonst weiter hochschaukeln.
     */
    @Test
    void refreshNowBlocksFurtherAttemptsAfterARateLimit() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveRateLimitException("Rate-Limit fuer /user/u-1/trackable_objects"));

        TractiveRateLimitException first =
                assertThrows(TractiveRateLimitException.class, () -> service.refreshNow());
        assertTrue(first.getMessage().contains("gesperrt"));

        TractiveRateLimitException second =
                assertThrows(TractiveRateLimitException.class, () -> service.refreshNow());
        assertTrue(second.getMessage().contains("Nächster Abruf"));
    }

    /** Zweimal kurz hintereinander druecken darf nicht zweimal die Cloud belasten. */
    @Test
    void refreshNowKeepsAMinimumGapBetweenForcedPolls() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        service.refreshNow();
        reset(apiClient);

        assertThrows(TractiveRateLimitException.class, () -> service.refreshNow());
        verifyNoInteractions(apiClient);
    }

    /** Der Nutzer soll die Cloud-Ursache sehen, statt sie nur im Log zu haben. */
    @Test
    void refreshNowPassesTheCloudFailureToTheCaller() {
        givenAuthenticated();
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));

        TractiveException ex = assertThrows(TractiveException.class, () -> service.refreshNow());

        assertTrue(ex.getMessage().contains("cloud down"));
    }

    @Test
    void refreshNowSaysSoWhenTheIntegrationIsDisabled() {
        properties.setEnabled(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.refreshNow());

        assertTrue(ex.getMessage().contains("deaktiviert"));
        verifyNoInteractions(apiClient, authService, entityStateService);
    }

    /**
     * Die Home-Entitaet darf bei einem Ausfall nicht 'unavailable' werden – der Tracker
     * ist zu Hause bewusst aus, und der letzte Wert ist genau die gewuenschte Aussage.
     */
    @Test
    void cloudFailureLeavesTheHomeEntityUntouched() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE, HOME_UPDATE));
        when(mapper.isHomeEntity(LOCATION_UPDATE)).thenReturn(false);
        when(mapper.isHomeEntity(HOME_UPDATE)).thenReturn(true);

        service.poll();
        reset(entityStateService);
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));

        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .noneMatch(update -> update.entityId().endsWith("_home")));
        assertTrue(captor.getAllValues().stream()
                .anyMatch(update -> update.entityId().endsWith("_location")
                        && "unavailable".equals(update.state())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void schreibtDiePositionenJedesPollZyklusMit() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any(), any())).thenReturn(List.of(LOCATION_UPDATE));

        // Ohne diesen Aufruf entstuende gar keine Historie - die Cloud liefert beim
        // Basic-Abo nur rund 24 Stunden, laengere Zeitraeume gibt es nur, weil wir
        // selbst mitschreiben.
        service.poll();

        ArgumentCaptor<List<TractivePetSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(positionRecorder).record(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("dev-9", captor.getValue().get(0).trackerId());
    }
}
