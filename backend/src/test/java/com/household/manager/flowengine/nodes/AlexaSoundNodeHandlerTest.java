package com.household.manager.flowengine.nodes;

import com.household.manager.alexa.AlexaException;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlexaSoundNodeHandlerTest {

    private static final NodeConfig CONFIG = new NodeConfig(Map.of(
            "sound", "amzn_sfx_doorbell_chime_01", "deviceSerials", List.of("WZ", "KU")));

    @Mock
    private AlexaAnnouncementService announcementService;

    @Mock
    private NodeContext ctx;

    @Test
    void passesMessageOnBeforeTheSoundIsPlayed() {
        List<Runnable> queued = new ArrayList<>();
        AlexaSoundNodeHandler handler = new AlexaSoundNodeHandler(announcementService, queued::add);
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "event.x"));

        NodeResult result = handler.handle(msg, CONFIG, ctx);

        assertFalse(result.outputs().isEmpty());
        verifyNoInteractions(announcementService);
        queued.forEach(Runnable::run);
        verify(announcementService).playSound("amzn_sfx_doorbell_chime_01", List.of("WZ", "KU"));
    }

    @Test
    void failedSoundDoesNotThrow() {
        doThrow(new AlexaException("weg")).when(announcementService).playSound(anyString(), anyList());
        AlexaSoundNodeHandler handler = new AlexaSoundNodeHandler(announcementService, Runnable::run);

        assertDoesNotThrow(() -> handler.handle(FlowMessage.of(Map.of()), CONFIG, ctx));
    }

    @Test
    void rejectedSoundIsReportedButFlowContinues() {
        Executor full = task -> { throw new RejectedExecutionException("voll"); };
        AlexaSoundNodeHandler handler = new AlexaSoundNodeHandler(announcementService, full);

        NodeResult result = handler.handle(FlowMessage.of(Map.of()), CONFIG, ctx);

        assertFalse(result.outputs().isEmpty());
        verify(ctx).reportFailure(anyString());
    }

    @Test
    void validateRequiresValidSoundAndDevices() {
        AlexaSoundNodeHandler handler = new AlexaSoundNodeHandler(announcementService, Runnable::run);
        assertEquals(2, handler.validate(NodeConfig.empty()).size());
        assertFalse(handler.validate(new NodeConfig(Map.of(
                "sound", "kein ton", "deviceSerials", List.of("WZ")))).isEmpty());
        assertTrue(handler.validate(CONFIG).isEmpty());
    }
}
