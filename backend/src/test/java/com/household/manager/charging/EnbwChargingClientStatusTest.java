package com.household.manager.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Statuscodes der Quelle gegen einen echten Loopback-Server (EnBW beantwortet unbekannte Ids mit 404, leerer Body). */
class EnbwChargingClientStatusTest {

    private HttpServer server;
    private EnbwChargingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chargestations/404", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/chargestations/500", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        ChargingProperties properties = new ChargingProperties();
        properties.getEnbw().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getEnbw().setApiKey("test");
        client = new EnbwChargingClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void unbekannteStationIstNotFound() {
        assertThatThrownBy(() -> client.stationDetails("404"))
                .isInstanceOf(ChargingStationNotFoundException.class);
    }

    @Test
    void serverfehlerIstKeinNotFound() {
        assertThatThrownBy(() -> client.stationDetails("500"))
                .isInstanceOf(ChargingSourceException.class)
                .isNotInstanceOf(ChargingStationNotFoundException.class);
    }
}
