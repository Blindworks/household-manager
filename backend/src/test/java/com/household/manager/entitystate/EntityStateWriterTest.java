package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityStateWriterTest {

    @Mock
    private EntityStateRepository repository;

    private EntityStateWriter writer;

    @BeforeEach
    void setUp() {
        writer = new EntityStateWriter(repository, new ObjectMapper());
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EntityStateUpdate update(String state) {
        return EntityStateUpdate.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state(state)
                .attributes(Map.of("unit", "°C"))
                .build();
    }

    @Test
    void newEntityIsCreatedAndEventReturned() {
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isPresent());
        assertEquals("unknown", event.get().oldState());
        assertEquals("21.5", event.get().newState());

        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        EntityState saved = captor.getValue();
        assertEquals("21.5", saved.getState());
        assertEquals(EntityDomain.SENSOR, saved.getDomain());
        assertNotNull(saved.getLastChanged());
        assertNotNull(saved.getLastUpdated());
    }

    @Test
    void unchangedStateBumpsLastUpdatedButReturnsNoEvent() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = existingEntity("21.5", earlier);
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isEmpty());
        assertEquals(earlier, existing.getLastChanged());
        assertTrue(existing.getLastUpdated().isAfter(earlier));
    }

    @Test
    void changedStateUpdatesLastChangedAndReturnsEvent() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = existingEntity("20.0", earlier);
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isPresent());
        assertEquals("20.0", event.get().oldState());
        assertEquals("21.5", event.get().newState());
        assertTrue(existing.getLastChanged().isAfter(earlier));
    }

    @Test
    void friendlyNameIsRefreshedOnEveryUpdate() {
        EntityState existing = existingEntity("21.5", LocalDateTime.now().minusHours(1));
        existing.setFriendlyName("Alter Name");
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        writer.upsert(update("21.5"));

        assertEquals("Wohnzimmer Temperatur", existing.getFriendlyName());
    }

    @Test
    void nullStateIsStoredAsUnknown() {
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = writer.upsert(update(null));

        assertTrue(event.isEmpty());
    }

    private EntityState existingEntity(String state, LocalDateTime timestamps) {
        return EntityState.builder()
                .id(1L)
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state(state)
                .lastChanged(timestamps)
                .lastUpdated(timestamps)
                .build();
    }
}
