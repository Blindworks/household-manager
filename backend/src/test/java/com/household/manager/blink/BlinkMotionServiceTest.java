package com.household.manager.blink;

import com.household.manager.blink.BlinkMotionService.MotionReport;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlinkMotionServiceTest {

    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private BlinkMotionService service;

    private static final MotionReport MOTION =
            new MotionReport("123", "Frontdoor", "42", "2026-08-27T12:00:00");

    @BeforeEach
    void setUp() {
        service = new BlinkMotionService(entityStateService);
    }

    @Test
    void feuertEreignisJeBewegung() {
        service.processMotions(List.of(MOTION));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        EntityStateUpdate event = captor.getValue();
        assertThat(event.entityId()).isEqualTo("event.blink_123_motion");
        assertThat(event.domain()).isEqualTo(EntityDomain.EVENT);
        assertThat(event.source()).isEqualTo(EntitySource.BLINK);
        assertThat(event.state()).isEqualTo("motion");
        assertThat(event.friendlyName()).isEqualTo("Frontdoor Bewegung");
        assertThat(event.attributes())
                .containsEntry("cameraName", "Frontdoor")
                .containsEntry("clipId", "42")
                .containsEntry("createdAt", "2026-08-27T12:00:00");
    }

    @Test
    void merktSichLetzteBewegungJeKamera() {
        service.processMotions(List.of(MOTION));

        var last = service.lastMotion("123").orElseThrow();
        assertThat(last.createdAt()).isEqualTo("2026-08-27T12:00:00");
        assertThat(last.clipId()).isEqualTo("42");
        assertThat(service.lastMotion("999")).isEmpty();
    }

    @Test
    void neuereBewegungUeberschreibtAeltere() {
        service.processMotions(List.of(MOTION));
        service.processMotions(List.of(
                new MotionReport("123", "Frontdoor", "43", "2026-08-27T13:00:00")));

        assertThat(service.lastMotion("123").orElseThrow().clipId()).isEqualTo("43");
    }

    @Test
    void eventFehlerVerhindertDasMerkenNicht() {
        doThrow(new RuntimeException("boom")).when(entityStateService).reportEvent(any());

        service.processMotions(List.of(MOTION));

        assertThat(service.lastMotion("123")).isPresent();
    }

    @Test
    void unvollstaendigeMeldungWirdUebersprungenOhneDieUebrigenZuVerlieren() {
        service.processMotions(java.util.Arrays.asList(
                new MotionReport("123", null, "42", "2026-08-27T12:00:00"),
                new MotionReport("456", "Garage", "43", "2026-08-27T12:05:00")));

        verify(entityStateService, times(1)).reportEvent(any());
        assertThat(service.lastMotion("123")).isEmpty();
        assertThat(service.lastMotion("456")).isPresent();
    }
}
