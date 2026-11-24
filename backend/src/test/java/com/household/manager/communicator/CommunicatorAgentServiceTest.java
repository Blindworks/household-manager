package com.household.manager.communicator;

import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentToolRegistry;
import com.household.manager.ai.AiException;
import com.household.manager.ai.AiProperties;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.ai.ToolResult;
import com.household.manager.communicator.CommunicatorDtos.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunicatorAgentServiceTest {

    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private AgentToolRegistry toolRegistry;
    @Mock
    private CommunicatorCardFactory cardFactory;
    @Mock
    private CommunicatorContextBuilder contextBuilder;

    private CommunicatorProperties properties;
    private AiProperties aiProperties;
    private CommunicatorAgentService service;

    private final Card climateCard = Card.builder().type(CommunicatorDtos.CARD_CLIMATE).title("Wohnzimmer").build();

    @BeforeEach
    void setUp() {
        properties = new CommunicatorProperties();
        properties.setMaxToolIterations(3);
        aiProperties = new AiProperties();
        aiProperties.setAnthropicApiKey("key");
        service = new CommunicatorAgentService(properties, aiProperties, anthropicApiClient, toolRegistry,
                cardFactory, contextBuilder);
        lenient().when(contextBuilder.build()).thenReturn("Zeit: jetzt");
        lenient().when(toolRegistry.toolDefinitions(AgentScope.TELEGRAM))
                .thenReturn(List.of(Map.of("name", "set_switch")));
        lenient().when(toolRegistry.toolDefinitions(AgentScope.COMMUNICATOR))
                .thenReturn(List.of(Map.of("name", "set_thermostat_target")));
        lenient().when(cardFactory.toolDefinitions()).thenReturn(List.of(Map.of("name", "show_climate")));
        lenient().when(cardFactory.handles("show_climate")).thenReturn(true);
    }

    private static AnthropicResponse toolUse(String... tools) {
        List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        for (String tool : tools) {
            blocks.add(Map.of("type", "tool_use", "id", "tu_" + tool, "name", tool, "input", Map.of()));
        }
        return new AnthropicResponse("tool_use", blocks);
    }

    private static AnthropicResponse endTurn(String text) {
        return new AnthropicResponse("end_turn", List.of(Map.of("type", "text", "text", text)));
    }

    @Test
    void reineKartenAntwortEndetOhneZweitenAufruf() {
        when(anthropicApiClient.createMessage(any())).thenReturn(toolUse("show_climate"));
        when(cardFactory.build(eq("show_climate"), anyMap())).thenReturn(climateCard);

        List<Card> cards = service.ask("Wie warm ist es im Wohnzimmer?");

        assertEquals(List.of(climateCard), cards);
        verify(anthropicApiClient, times(1)).createMessage(any());
    }

    @Test
    void modellSiehtAlleDreiWerkzeugGruppenUndDenHauszustand() {
        when(anthropicApiClient.createMessage(any())).thenReturn(toolUse("show_climate"));
        when(cardFactory.build(eq("show_climate"), anyMap())).thenReturn(climateCard);

        service.ask("Frage");

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(anthropicApiClient).createMessage(captor.capture());
        List<Object> names = captor.getValue().tools().stream().map(tool -> tool.get("name")).toList();
        assertEquals(List.of("set_switch", "set_thermostat_target", "show_climate"), names);
        assertTrue(String.valueOf(captor.getValue().system().get(0).get("text")).endsWith("Zeit: jetzt"));
    }

    @Test
    void aktionLaeuftImPassendenScopeUndDerLaufGehtWeiter() {
        when(anthropicApiClient.createMessage(any()))
                .thenReturn(toolUse("set_thermostat_target", "set_switch"))
                .thenReturn(toolUse("show_climate"));
        when(toolRegistry.execute(eq(AgentScope.COMMUNICATOR), eq("set_thermostat_target"), anyMap()))
                .thenReturn(ToolResult.ok("ok"));
        when(toolRegistry.execute(eq(AgentScope.TELEGRAM), eq("set_switch"), anyMap()))
                .thenReturn(ToolResult.ok("ok"));
        when(cardFactory.build(eq("show_climate"), anyMap())).thenReturn(climateCard);

        List<Card> cards = service.ask("Stell das Wohnzimmer auf 21");

        assertEquals(List.of(climateCard), cards);
        verify(anthropicApiClient, times(2)).createMessage(any());
    }

    @Test
    void abgelehnteKarteGehtAlsFehlerAnsModellZurueck() {
        when(anthropicApiClient.createMessage(any()))
                .thenReturn(toolUse("show_climate"))
                .thenReturn(endTurn("Den Raum kenne ich nicht."));
        when(cardFactory.build(eq("show_climate"), anyMap()))
                .thenThrow(new IllegalArgumentException("Unbekanntes Thermostat: 99"));

        List<Card> cards = service.ask("Wie warm ist es im Turm?");

        assertEquals(1, cards.size());
        assertEquals(CommunicatorDtos.CARD_INFO, cards.get(0).type());
        assertEquals("Den Raum kenne ich nicht.", cards.get(0).text());
    }

    @Test
    void mehrAlsDreiKartenWerdenNichtGezeigt() {
        when(anthropicApiClient.createMessage(any()))
                .thenReturn(toolUse("show_climate", "show_climate", "show_climate", "show_climate"))
                .thenReturn(endTurn(""));
        when(cardFactory.build(eq("show_climate"), anyMap())).thenReturn(climateCard);

        assertEquals(CommunicatorAgentService.MAX_CARDS, service.ask("Alles").size());
    }

    @Test
    void claudeFehlerWirdZurFehlerKarte() {
        when(anthropicApiClient.createMessage(any())).thenThrow(new AiException("weg", null));

        List<Card> cards = service.ask("Frage");

        assertEquals(CommunicatorAgentService.ERROR_TEXT, cards.get(0).text());
    }

    @Test
    void ohneSchluesselWirdClaudeNieGefragt() {
        aiProperties.setAnthropicApiKey("");

        List<Card> cards = service.ask("Frage");

        assertEquals(CommunicatorAgentService.NOT_CONFIGURED_TEXT, cards.get(0).text());
        verify(anthropicApiClient, never()).createMessage(any());
    }

    @Test
    void endlosschleifeEndetMitFehlerKarte() {
        when(anthropicApiClient.createMessage(any())).thenReturn(toolUse("set_switch"));
        when(toolRegistry.execute(eq(AgentScope.TELEGRAM), eq("set_switch"), anyMap()))
                .thenReturn(ToolResult.ok("ok"));

        List<Card> cards = service.ask("Frage");

        assertEquals(CommunicatorAgentService.ERROR_TEXT, cards.get(0).text());
        verify(anthropicApiClient, times(3)).createMessage(any());
    }
}
