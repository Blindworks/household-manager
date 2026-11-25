package com.household.manager.communicator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiCatalog catalog = mock(ApiCatalog.class);
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private HttpServer server;
    private ApiGateway gateway;
    private int status = 200;
    private String body = "[{\"name\":\"Flur\"},{\"name\":\"Küche\"}]";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            uri.set(exchange.getRequestURI().toString());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local.server.port", String.valueOf(server.getAddress().getPort()))
                .withProperty("server.servlet.context-path", "/api");
        gateway = new ApiGateway(catalog, mapper, environment);
        when(catalog.find("GET", "/v1/switches")).thenReturn(Optional.of(mock(ApiCatalog.Endpoint.class)));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiGateway.Result get(String filter) {
        return gateway.get("/v1/switches", Map.of("view", "a&b=c"), filter, new ApiGateway.Caller("S=1", null));
    }

    @Test
    void reichtDieAnmeldungDurchUndKodiertParameter() {
        assertThat(get("").data()).hasSize(2);
        assertThat(cookie.get()).isEqualTo("S=1");
        assertThat(uri.get()).isEqualTo("/api/v1/switches?view=a%26b%3Dc");
    }

    @Test
    void entferntZugangsdatenAuchVerschachtelt() {
        body = "[{\"name\":\"x\",\"access_token\":\"t\",\"a\":{\"apiKey\":\"k\",\"pin\":1234},\"inputTokens\":5},"
                + "{\"key\":\"hub_token\",\"value\":\"geheim\"}]";
        assertThat(get("").data().toString())
                .isEqualTo("[{\"name\":\"x\",\"a\":{},\"inputTokens\":5},{\"key\":\"hub_token\"}]");
    }

    @Test
    void filterFindetKeineEntferntenZugangsdaten() {
        body = "[{\"name\":\"x\",\"token\":\"geheim\"}]";
        assertThat(get("geheim").data()).isEmpty();
    }

    @Test
    void filtertListen() {
        assertThat(get("küche").data().toString()).isEqualTo("[{\"name\":\"Küche\"}]");
    }

    @Test
    void ablehnungDurchDieSicherheitsregelnWirdZurMeldung() {
        status = 403;
        assertThatThrownBy(() -> get("")).hasMessageContaining("403");
    }

    @Test
    void unbekannterPfadWirdNieAufgerufen() {
        when(catalog.find("GET", "/x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> gateway.get("/x", Map.of(), "", new ApiGateway.Caller(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(uri.get()).isNull();
    }

    @Test
    void grosseListenWerdenGekuerztUndBleibenGueltig() {
        ArrayNode list = mapper.createArrayNode();
        for (int i = 0; i < 1000; i++) {
            list.addObject().put("name", "Eintrag " + i).put("wert", i);
        }
        ApiGateway.Result result = gateway.shorten(mapper.createObjectNode().set("points", list));
        assertThat(result.shortened()).isTrue();
        assertThat(result.total()).isEqualTo(1000);
        assertThat(result.data().path("points")).hasSize(result.shown());
        assertThat(result.data().toString().length()).isLessThanOrEqualTo(ApiGateway.MAX_RESULT_CHARS);
    }
}
