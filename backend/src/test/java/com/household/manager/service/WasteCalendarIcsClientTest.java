package com.household.manager.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testet gegen einen echten lokalen HTTP-Server (JDK-eigener {@link HttpServer}, keine
 * zusaetzliche Abhaengigkeit): Der Client besteht im Wesentlichen aus HTTP-Verhalten,
 * das ein Mock nur nachbauen, aber nicht belegen wuerde.
 */
class WasteCalendarIcsClientTest {

    private static final String ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";
    private static final String HTML = "<html><body>Kalenderansicht</body></html>";

    private HttpServer server;
    private WasteCalendarIcsClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        client = new WasteCalendarIcsClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Startet den Server mit einer festen Antwort und liefert die URL dazu. */
    private String urlServing(int status, String contentType, String body) {
        server.createContext("/kalender", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/kalender";
    }

    @Test
    void liefertDenIcsTextZurueck() {
        String url = urlServing(200, "text/calendar; charset=UTF-8", ICS);

        assertThat(client.fetch(url)).isEqualTo(ICS);
    }

    @Test
    void akzeptiertIcsAuchWennDerServerTextPlainMeldet() {
        // Nicht jeder Server deklariert text/calendar; entscheidend ist der Inhalt.
        String url = urlServing(200, "text/plain", ICS);

        assertThat(client.fetch(url)).isEqualTo(ICS);
    }

    @Test
    void akzeptiertEineDateiMitBomUndFuehrenderLeerzeile() {
        String url = urlServing(200, "text/calendar", "﻿\r\n" + ICS);

        assertThat(client.fetch(url)).contains("BEGIN:VCALENDAR");
    }

    @Test
    void weistEineHtmlSeiteZurueckStattSieDemParserZuGeben() {
        // Der Einbetten-Link eines Google-Kalenders antwortet genau so: HTML mit Status 200.
        String url = urlServing(200, "text/html; charset=utf-8", HTML);

        assertThatThrownBy(() -> client.fetch(url))
                .isInstanceOf(WasteCalendarException.class)
                .hasMessageContaining("liefert keinen Kalender")
                .hasMessageContaining("text/html");
    }

    @Test
    void weistHtmlAuchDannZurueckWennDerServerTextCalendarBehauptet() {
        String url = urlServing(200, "text/calendar", HTML);

        assertThatThrownBy(() -> client.fetch(url))
                .isInstanceOf(WasteCalendarException.class)
                .hasMessageContaining("liefert keinen Kalender");
    }

    @Test
    void meldetEinenFehlerstatusUnveraendert() {
        String url = urlServing(401, "text/html", HTML);

        assertThatThrownBy(() -> client.fetch(url))
                .isInstanceOf(WasteCalendarException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void meldetEineUngueltigeUrl() {
        assertThatThrownBy(() -> client.fetch("kein-url"))
                .isInstanceOf(WasteCalendarException.class);
    }
}
