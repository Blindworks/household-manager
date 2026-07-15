package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityStateServiceTest {

    @Mock
    private EntityStateWriter writer;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EntityStateRepository repository;

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

    @Test
    void setCustomNameTrimsAndPersists() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder().entityId("sensor.x").friendlyName("Langer Name").build();
        when(repository.findByEntityId("sensor.x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setCustomName("sensor.x", "  Kurz  ");

        assertTrue(result.isPresent());
        assertEquals("Kurz", result.get().getCustomName());
    }

    @Test
    void setCustomNameClearsWhenBlank() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder()
                .entityId("sensor.x").friendlyName("Langer Name").customName("Alt").build();
        when(repository.findByEntityId("sensor.x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setCustomName("sensor.x", "   ");

        assertTrue(result.isPresent());
        assertNull(result.get().getCustomName());
    }

    @Test
    void setCustomNameReturnsEmptyForUnknownEntity() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        when(repository.findByEntityId("sensor.unknown")).thenReturn(Optional.empty());

        Optional<EntityState> result = svc.setCustomName("sensor.unknown", "Kurz");

        assertFalse(result.isPresent());
    }
}
