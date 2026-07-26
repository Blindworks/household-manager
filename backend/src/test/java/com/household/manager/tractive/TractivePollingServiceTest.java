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
        service = new TractivePollingService(properties, apiClient, authService, mapper, entityStateService);
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
}
