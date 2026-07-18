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
import static org.mockito.Mockito.mock;
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
        assertEquals(EntityStateWriter.STATE_UNKNOWN, event.get().oldState());
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

    @Test
    void eventCarriesRawAttributesMap() {
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertEquals(Map.of("unit", "°C"), event.orElseThrow().attributes());
    }

    @Test
    void serializationFailureStoresNullAttributesButStillSavesState() {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        try {
            when(failingMapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        EntityStateWriter failingWriter = new EntityStateWriter(repository, failingMapper);
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = failingWriter.upsert(update("21.5"));

        assertTrue(event.isPresent());
        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getAttributes());
        assertEquals("21.5", captor.getValue().getState());
    }

    @Test
    void upsertPreservesUserSetCustomName() {
        EntityState existing = EntityState.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .customName("Küche")
                .state("21.5")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature"))
                .thenReturn(Optional.of(existing));

        writer.upsert(update("22.0"));

        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        assertEquals("Küche", captor.getValue().getCustomName());
    }

    private EntityStateUpdate eventUpdate(String action) {
        return EntityStateUpdate.builder()
                .entityId("event.zigbee_flur_taster_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Flur-Taster")
                .friendlyName("Flur-Taster Taster")
                .state(action)
                .attributes(Map.of("deviceClass", "button"))
                .build();
    }

    @Test
    void upsertEventCreatesEntityOnFirstPress() {
        when(repository.findByEntityId("event.zigbee_flur_taster_action")).thenReturn(Optional.empty());

        EntityEventFired event = writer.upsertEvent(eventUpdate("double"));

        assertEquals("double", event.action());
        assertEquals("event.zigbee_flur_taster_action", event.entityId());
        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        assertEquals("double", captor.getValue().getState());
        assertEquals(EntityDomain.EVENT, captor.getValue().getDomain());
        assertNotNull(captor.getValue().getLastChanged());
    }

    @Test
    void upsertEventAlwaysFiresAndBumpsLastChangedEvenWhenActionUnchanged() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = EntityState.builder()
                .id(2L)
                .entityId("event.zigbee_flur_taster_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Flur-Taster")
                .friendlyName("Flur-Taster Taster")
                .state("single")
                .lastChanged(earlier)
                .lastUpdated(earlier)
                .build();
        when(repository.findByEntityId("event.zigbee_flur_taster_action")).thenReturn(Optional.of(existing));

        EntityEventFired event = writer.upsertEvent(eventUpdate("single"));

        assertEquals("single", event.action());
        assertTrue(existing.getLastChanged().isAfter(earlier));
        assertTrue(existing.getLastUpdated().isAfter(earlier));
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
