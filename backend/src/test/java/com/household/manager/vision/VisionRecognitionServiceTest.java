package com.household.manager.vision;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.VisionRecognition;
import com.household.manager.repository.VisionRecognitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisionRecognitionServiceTest {

    @Mock
    private VisionRecognitionRepository repository;
    @Mock
    private EntityStateService entityStateService;

    private final Instant start = Instant.parse("2026-07-22T10:00:00Z");
    private final AtomicReference<Instant> currentTime = new AtomicReference<>(start);
    private VisionProperties properties;
    private VisionRecognitionService service;

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
        // Verstellbare Uhr statt eines zweiten Service-Objekts: der Heartbeat-Test
        // rueckt die Zeit vor, ohne den Service (und damit den geteilten Mock-Zustand) neu aufzubauen.
        Clock adjustableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("Europe/Berlin");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentTime.get();
            }
        };
        service = new VisionRecognitionService(repository, entityStateService, properties, adjustableClock);
    }

    @Test
    void recognitionIsPersistedAndFiredAsEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(
                List.of(new VisionRecognitionService.RecognizedPerson(1L, "Benedikt", new BigDecimal("0.7234"))),
                0, new byte[]{9}));

        ArgumentCaptor<VisionRecognition> saved = ArgumentCaptor.forClass(VisionRecognition.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPersonName()).isEqualTo("Benedikt");

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(update.capture());
        assertThat(update.getValue().entityId()).isEqualTo("event.vision_blink_door_person");
        assertThat(update.getValue().domain()).isEqualTo(EntityDomain.EVENT);
        assertThat(update.getValue().source()).isEqualTo(EntitySource.VISION);
        assertThat(update.getValue().state()).isEqualTo("Benedikt");
        assertThat(update.getValue().attributes()).containsEntry("personId", 1L);
    }

    @Test
    void unknownOnlyRecognitionFiresUnknownEvent() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(List.of(), 2, null));

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(update.capture());
        assertThat(update.getValue().state()).isEqualTo("unknown");
        assertThat(update.getValue().attributes()).containsEntry("unknownFaces", 2);
    }

    @Test
    void persistFailureStillFiresEvent() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));

        service.processRecognition(new VisionRecognitionService.RecognitionReport(
                List.of(new VisionRecognitionService.RecognizedPerson(1L, "Benedikt", new BigDecimal("0.7"))),
                0, null));

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void staleHeartbeatMarksEntityUnavailable() {
        service.heartbeat();
        service.checkHeartbeat();  // frisch -> nichts
        verify(entityStateService, never()).reportState(any());

        currentTime.set(start.plusSeconds(properties.getHeartbeatTimeoutSeconds() + 1));
        service.checkHeartbeat();

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(update.capture());
        assertThat(update.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void freshRecognitionResetsUnavailableFlagSoItCanFireAgain() {
        currentTime.set(start.plusSeconds(properties.getHeartbeatTimeoutSeconds() + 1));
        service.heartbeat();
        service.checkHeartbeat();
        verify(entityStateService, never()).reportState(any());

        currentTime.set(currentTime.get().plusSeconds(properties.getHeartbeatTimeoutSeconds() + 1));
        service.checkHeartbeat();

        verify(entityStateService, times(1)).reportState(any());
    }
}
