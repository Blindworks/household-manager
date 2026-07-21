package com.household.manager.nuki;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.NukiEntityMapper;
import com.household.manager.nuki.dto.NukiSmartlockDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NukiPollingServiceTest {

    @Mock
    private NukiApiClient apiClient;
    @Mock
    private NukiEntityMapper mapper;
    @Mock
    private EntityStateService entityStateService;

    private NukiProperties properties;
    private NukiPollingService service;

    private static final EntityStateUpdate LOCK_UPDATE = EntityStateUpdate.builder()
            .entityId("lock.nuki_1")
            .domain(EntityDomain.LOCK)
            .source(EntitySource.NUKI)
            .sourceRef("1")
            .friendlyName("Haustür")
            .state("locked")
            .attributes(Map.of())
            .build();

    @BeforeEach
    void setUp() {
        properties = new NukiProperties();
        properties.setApiToken("token");
        service = new NukiPollingService(properties, apiClient, mapper, entityStateService);
    }

    @Test
    void pollReportsMappedStates() {
        NukiSmartlockDto dto = new NukiSmartlockDto(1L, "Haustür", null);
        when(apiClient.listSmartlocks()).thenReturn(List.of(dto));
        when(mapper.map(dto)).thenReturn(List.of(LOCK_UPDATE));

        service.poll();

        verify(entityStateService).reportState(LOCK_UPDATE);
    }

    @Test
    void pollFailureMarksLastKnownEntitiesUnavailable() {
        NukiSmartlockDto dto = new NukiSmartlockDto(1L, "Haustür", null);
        when(apiClient.listSmartlocks())
                .thenReturn(List.of(dto))
                .thenThrow(new NukiException("down", null));
        when(mapper.map(dto)).thenReturn(List.of(LOCK_UPDATE));

        service.poll();
        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate unavailable = captor.getAllValues().get(1);
        assertEquals("lock.nuki_1", unavailable.entityId());
        assertEquals("unavailable", unavailable.state());
    }

    @Test
    void pollFailureWithUnexpectedErrorMarksUnavailable() {
        NukiSmartlockDto dto = new NukiSmartlockDto(1L, "Haustür", null);
        when(apiClient.listSmartlocks())
                .thenReturn(List.of(dto))
                .thenThrow(new RuntimeException("boom"));
        when(mapper.map(dto)).thenReturn(List.of(LOCK_UPDATE));

        service.poll();
        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        EntityStateUpdate unavailable = captor.getAllValues().get(1);
        assertEquals("lock.nuki_1", unavailable.entityId());
        assertEquals("unavailable", unavailable.state());
    }

    @Test
    void doesNothingWithoutToken() {
        properties.setApiToken("");
        service.poll();
        verifyNoInteractions(apiClient, entityStateService);
    }

    @Test
    void mapperErrorsDoNotAbortPolling() {
        NukiSmartlockDto broken = new NukiSmartlockDto(1L, "Kaputt", null);
        NukiSmartlockDto ok = new NukiSmartlockDto(2L, "Haustür", null);
        when(apiClient.listSmartlocks()).thenReturn(List.of(broken, ok));
        when(mapper.map(broken)).thenThrow(new IllegalStateException("boom"));
        when(mapper.map(ok)).thenReturn(List.of(LOCK_UPDATE));

        assertDoesNotThrow(() -> service.poll());
        verify(entityStateService).reportState(LOCK_UPDATE);
    }
}
