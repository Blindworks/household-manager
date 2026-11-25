package com.household.manager.communicator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ai.AiException;
import com.household.manager.ai.AiProperties;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.communicator.CommunicatorDtos.Card;
import com.household.manager.model.entity.CommunicatorRequest.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunicatorAgentServiceTest {

    private static final ApiGateway.Caller CALLER = new ApiGateway.Caller("JSESSIONID=x", null);

    @Mock
    private AnthropicApiClient client;
    @Mock
    private ApiCatalog catalog;
    @Mock
    private ApiGateway gateway;
    @Mock
    private ActionConfirmation confirmation;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProperties ai = new AiProperties();
    private final CommunicatorTrace trace = CommunicatorTrace.text();
    private CommunicatorAgentService service;

    @BeforeEach
    void setUp() {
        ai.setAnthropicApiKey("key");
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T18:50:00Z"), ZoneId.of("Europe/Berlin"));
        service = new CommunicatorAgentService(new CommunicatorProperties(), ai, client, catalog, gateway,
                new CardBuilder(catalog, clock, confirmation), mapper, clock);
        lenient().when(catalog.index()).thenReturn("VERZEICHNIS");
    }

    private static AnthropicResponse tool(String name, Map<String, Object> input) {
        return new AnthropicResponse("tool_use",
                List.of(Map.of("type", "tool_use", "id", "t", "name", name, "input", input)));
    }

    private void antwortet(String json) throws Exception {
        when(gateway.get(anyString(), anyMap(), anyString(), eq(CALLER)))
                .thenReturn(new ApiGateway.Result(mapper.readTree(json), 1, 1));
    }

    @Test
    void liestMitDerAnmeldungDesNutzersUndZeigtDenEchtenWert() throws Exception {
        antwortet("[{\"start\":\"2026-11-24T17:05:00Z\",\"distanceMeters\":2100}]");
        when(client.createMessage(any()))
                .thenReturn(tool("api_get", Map.of("path", "/v1/walks")))
                .thenReturn(tool("show_card", Map.of("title", "Toni", "blocks", List.of(
                        Map.of("type", "value", "ref", "r1", "pointer", "/0/distanceMeters", "factor", 0.001,
                                "decimals", 1, "unit", "km"),
                        Map.of("type", "value", "ref", "r1", "pointer", "/0/start")))));

        List<Card> cards = service.ask("War Tony spazieren?", CALLER, trace);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).blocks().get(0).value()).isEqualTo("2,1");
        assertThat(cards.get(0).blocks().get(1).value()).isEqualTo("18:05");
        verify(client, times(2)).createMessage(any());
    }

    @Test
    void falscherZeigerGehtAlsFehlerZurueckStattEinenWertZuErfinden() throws Exception {
        antwortet("[]");
        when(client.createMessage(any()))
                .thenReturn(tool("api_get", Map.of("path", "/v1/walks")))
                .thenReturn(tool("show_card", Map.of("title", "x", "blocks", List.of(
                        Map.of("type", "value", "ref", "r1", "pointer", "/0/start")))))
                .thenReturn(tool("show_card", Map.of("title", "x", "blocks", List.of(
                        Map.of("type", "text", "text", "Keine Runde gefunden.")))));

        assertThat(service.ask("?", CALLER, trace).get(0).blocks().get(0).text()).isEqualTo("Keine Runde gefunden.");
    }

    @Test
    void aktionWirdNurAngebotenNieAusgefuehrt() {
        when(catalog.find("POST", "/v1/switches/switch.flur/toggle"))
                .thenReturn(Optional.of(mock(ApiCatalog.Endpoint.class)));
        when(client.createMessage(any())).thenReturn(tool("show_card", Map.of("title", "Flur", "blocks", List.of(),
                "actions", List.of(Map.of("label", "Flur einschalten", "method", "POST",
                        "path", "/v1/switches/switch.flur/toggle")))));

        Card card = service.ask("Licht an", CALLER, trace).get(0);

        assertThat(card.actions()).hasSize(1);
        verify(gateway, never()).get(any(), any(), any(), any());
    }

    @Test
    void erfundeneAktionWirdAbgelehnt() {
        when(client.createMessage(any())).thenReturn(tool("show_card", Map.of("title", "x", "blocks", List.of(),
                        "actions", List.of(Map.of("label", "x", "method", "POST", "path", "/v1/erfunden")))))
                .thenReturn(new AnthropicResponse("end_turn",
                        List.of(Map.of("type", "text", "text", "Geht nicht."))));

        Card card = service.ask("?", CALLER, trace).get(0);

        assertThat(card.actions()).isEmpty();
        assertThat(card.blocks().get(0).text()).isEqualTo("Geht nicht.");
    }

    @Test
    void schreibendesWerkzeugGibtEsNicht() {
        assertThat(CommunicatorTools.ALL).extracting(t -> t.get("name"))
                .containsExactly("api_get", "api_describe", "show_card");
    }

    @Test
    void verzeichnisStehtImZwischengespeichertenPromptTeil() {
        when(client.createMessage(any())).thenThrow(new AiException("weg", null));

        assertThat(service.ask("?", CALLER, trace).get(0).blocks().get(0).text())
                .isEqualTo(CommunicatorAgentService.ERROR_TEXT);

        var captor = org.mockito.ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(client).createMessage(captor.capture());
        Map<String, Object> first = captor.getValue().system().get(0);
        assertThat(String.valueOf(first.get("text"))).endsWith("VERZEICHNIS");
        assertThat(first).containsKey("cache_control");
    }

    @Test
    void verlaufHaeltTokensWerkzeugeUndProblemeFest() throws Exception {
        antwortet("[]");
        when(client.createMessage(any()))
                .thenReturn(new AnthropicResponse("tool_use", List.of(Map.of("type", "tool_use", "id", "t",
                        "name", "api_get", "input", Map.of("path", "/v1/walks"))), 10, 5, 900, 100))
                .thenReturn(tool("show_card", Map.of("title", "x", "blocks", List.of(
                        Map.of("type", "value", "ref", "r1", "pointer", "/0/start")))))
                .thenThrow(new AiException("overloaded", null));

        service.ask("Frage", CALLER, trace);

        assertThat(trace.getSteps()).extracting(s -> s.type() + " " + s.name() + " " + s.ok()).containsExactly(
                "CLAUDE tool_use true", "TOOL api_get true", "CLAUDE tool_use true", "TOOL show_card false",
                "CLAUDE error false");
        assertThat(trace.getSteps().get(0).cacheReadTokens()).isEqualTo(900);
        assertThat(trace.getEndpoints()).containsExactly("/v1/walks");
        assertThat(trace.getStatus()).isEqualTo(Status.AI_ERROR);
        assertThat(trace.getError()).isEqualTo("overloaded");
        assertThat(trace.getCards()).hasSize(1);
    }

    @Test
    void ohneSchluesselWirdClaudeNieGefragt() {
        ai.setAnthropicApiKey("");

        service.ask("?", CALLER, trace);

        verify(client, never()).createMessage(any());
    }
}
