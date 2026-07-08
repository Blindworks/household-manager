package com.household.manager.entitystate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityStateServiceTest {

    @Mock
    private EntityStateWriter writer;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private EntityStateService service;

    @BeforeEach
    void setUp() {
        // Repository is deliberately not mocked: reportState() never touches it,
        // and an unused @Mock field would be dead test setup.
        service = new EntityStateService(writer, null, eventPublisher);
    }

    private EntityStateUpdate update() {
        return EntityStateUpdate.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Steckdose")
                .state("on")
                .attributes(Map.of())
                .build();
    }

    @Test
    void publishesEventWhenWriterReportsChange() {
        EntityStateChangedEvent event = new EntityStateChangedEvent(
                "switch.kasa_abc", "off", "on", Map.of(), LocalDateTime.now());
        when(writer.upsert(any())).thenReturn(Optional.of(event));

        service.reportState(update());

        verify(eventPublisher).publishEvent(event);
    }

    @Test
    void publishesNoEventWhenStateUnchanged() {
        when(writer.upsert(any())).thenReturn(Optional.empty());

        service.reportState(update());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void swallowsWriterExceptionsSoCallerIsNeverBroken() {
        when(writer.upsert(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> service.reportState(update()));
    }

    @Test
    void swallowsListenerExceptionsFromEventPublishing() {
        when(writer.upsert(any())).thenReturn(Optional.of(new EntityStateChangedEvent(
                "switch.kasa_abc", "off", "on", Map.of(), LocalDateTime.now())));
        doThrow(new RuntimeException("listener failed")).when(eventPublisher).publishEvent(any(Object.class));

        assertDoesNotThrow(() -> service.reportState(update()));
    }
}
