package com.household.manager.tesla;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeslaVehicleImageServiceTest {

    private static final String OWN = "https://example.com/auto.png";

    private final TeslaSettingsService settings = mock(TeslaSettingsService.class);
    private final TeslaVehicleService vehicles = mock(TeslaVehicleService.class);
    private final HttpClient http = mock(HttpClient.class);
    private TeslaVehicleImageService service;

    @BeforeEach
    void setUp() {
        service = new TeslaVehicleImageService(settings, vehicles,
                Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneId.of("Europe/Berlin")), http);
    }

    @Test
    void ohneAdresseKeinBild() {
        when(settings.current()).thenReturn(new TeslaSettings("t", null, 5, null));

        assertThat(service.image()).isEmpty();
        assertThat(service.version()).isNull();
    }

    @Test
    void eigeneAdresseGewinntUndWirdZwischengespeichert() throws Exception {
        when(settings.current()).thenReturn(new TeslaSettings("t", null, 5, OWN));
        respond(200, "image/png", new byte[]{1, 2, 3});

        assertThat(service.image()).hasValueSatisfying(img -> assertThat(img.contentType()).isEqualTo("image/png"));
        service.image();

        verify(http, times(1)).send(any(HttpRequest.class), any());
        assertThat(service.version()).hasSize(12);
    }

    @Test
    void keinBildIstEinFehler() throws Exception {
        when(settings.current()).thenReturn(new TeslaSettings("t", null, 5, OWN));
        respond(200, "text/html", new byte[]{1});

        assertThatThrownBy(() -> service.image()).isInstanceOf(TeslaException.class);
        // nach einem Fehlschlag nicht gleich wieder fragen
        assertThatThrownBy(() -> service.image()).isInstanceOf(TeslaException.class);
        verify(http, times(1)).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String contentType, byte[] body) throws Exception {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (a, b) -> true));
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
    }
}
