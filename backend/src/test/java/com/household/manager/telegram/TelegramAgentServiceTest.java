package com.household.manager.telegram;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramAgentServiceTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private TelegramToolRegistry toolRegistry;

    private TelegramProperties props;
    private TelegramConversationStore store;
    private TelegramAgentService service;

    @BeforeEach
    void setUp() {
        props = new TelegramProperties();
        props.setMaxToolIterations(3);
        store = new TelegramConversationStore(props);
        service = new TelegramAgentService(props, anthropicApiClient, toolRegistry, store);
        lenient().when(toolRegistry.toolDefinitions()).thenReturn(List.of());
    }

    private AnthropicResponse endTurn(String text) {
        return new AnthropicResponse("end_turn", List.of(Map.of("type", "text", "text", text)));
    }

    private AnthropicResponse toolUse(String id, String tool) {
        return new AnthropicResponse("tool_use", List.of(
                Map.of("type", "tool_use", "id", id, "name", tool, "input", Map.of())));
    }

    @Test
    void plainAnswerWithoutTools() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(endTurn("Hallo!"));

        assertEquals("Hallo!", service.handleUserMessage(1L, "hi"));
    }

    @Test
    void executesToolsAndFeedsResultsBack() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(toolUse("tu_1", "list_switches"))
                .thenReturn(endTurn("2 Lampen sind an."));
        when(toolRegistry.execute("list_switches", Map.of())).thenReturn(ToolResult.ok("[...]"));

        String answer = service.handleUserMessage(1L, "Was ist an?");

        assertEquals("2 Lampen sind an.", answer);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AnthropicMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(anthropicApiClient, times(2)).createMessage(anyString(), captor.capture(), anyList());
        List<AnthropicMessage> secondCall = captor.getAllValues().get(1);
        AnthropicMessage toolResultMessage = secondCall.get(secondCall.size() - 1);
        assertEquals("user", toolResultMessage.role());
        assertEquals("tool_result", toolResultMessage.content().get(0).get("type"));
        assertEquals("tu_1", toolResultMessage.content().get(0).get("tool_use_id"));
    }

    @Test
    void stopsAfterMaxIterations() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(toolUse("tu_1", "list_switches"));
        when(toolRegistry.execute(anyString(), anyMap())).thenReturn(ToolResult.ok("x"));

        String answer = service.handleUserMessage(1L, "loop");

        verify(anthropicApiClient, times(3)).createMessage(anyString(), anyList(), anyList());
        assertFalse(answer.isBlank());
    }

    @Test
    void apiErrorYieldsFriendlyMessage() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenThrow(new TelegramException("kaputt", null));

        String answer = service.handleUserMessage(1L, "hi");

        assertFalse(answer.isBlank());
        assertFalse(answer.contains("kaputt"));
    }

    @Test
    void setztDenTelegramAktorWaehrendDerToolAusfuehrungUndLoeschtIhnDanach() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(toolUse("tu_1", "list_switches"))
                .thenReturn(endTurn("2 Lampen sind an."));
        AtomicReference<AuditActor> actorDuringTool = new AtomicReference<>();
        when(toolRegistry.execute(eq("list_switches"), anyMap())).thenAnswer(invocation -> {
            actorDuringTool.set(AuditActorContext.get());
            return ToolResult.ok("[...]");
        });

        service.handleUserMessage(7L, "Was ist an?");

        assertEquals(AuditActor.telegram(7L), actorDuringTool.get());
        assertNull(AuditActorContext.get());
    }

    @Test
    void loeschtDenTelegramAktorAuchNachEinemFehler() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenThrow(new TelegramException("kaputt", null));

        service.handleUserMessage(7L, "hi");

        assertNull(AuditActorContext.get());
    }

    @Test
    void successfulExchangeLandsInHistory() {
        when(anthropicApiClient.createMessage(anyString(), anyList(), anyList()))
                .thenReturn(endTurn("Antwort"));

        service.handleUserMessage(1L, "Frage");

        assertEquals(2, store.history(1L).size());
    }
}
