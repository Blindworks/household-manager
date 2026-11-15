package com.household.manager.grocery.source;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroceryHttpTest {

    private static final URI URL = URI.create("https://example.test/x");

    private static GroceryHttp http(int status, Map<String, List<String>> headers) {
        return new GroceryHttp(new FakeHttpTransport().respond("https://example.test/", status, "body", headers));
    }

    @Test
    void returnsBodyOnSuccessAndSendsBrowserUserAgent() throws SourceFetchException {
        FakeHttpTransport transport = new FakeHttpTransport().respond("https://example.test/", 200, "ok");

        assertThat(new GroceryHttp(transport).get(URL, Map.of("x-test", "1"))).isEqualTo("ok");

        assertThat(transport.requests().get(0).headers().firstValue("User-Agent").orElse("")).contains("Mozilla");
        assertThat(transport.requests().get(0).headers().firstValue("x-test")).contains("1");
    }

    @Test
    void blockedStatusAndCloudflareHeaderReportAccessBlocked() {
        assertThatThrownBy(() -> http(403, Map.of()).get(URL, Map.of()))
                .hasMessage("Zugriff gesperrt (HTTP 403)");
        assertThatThrownBy(() -> http(429, Map.of()).get(URL, Map.of()))
                .hasMessage("Zugriff gesperrt (HTTP 429)");
        assertThatThrownBy(() -> http(200, Map.of("Cf-Mitigated", List.of("challenge"))).get(URL, Map.of()))
                .hasMessage("Zugriff gesperrt (HTTP 200)");
    }

    @Test
    void otherErrorStatusReportsHttpCode() {
        assertThatThrownBy(() -> http(404, Map.of()).get(URL, Map.of())).hasMessage("HTTP 404");
    }
}
