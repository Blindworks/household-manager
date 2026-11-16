package com.household.manager.grocery.source;

import com.household.manager.grocery.GroceryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Gemeinsamer HTTP-Zugriff aller Angebotsquellen (HTTP/1.1, Browser-User-Agent, Timeout aus den Properties). */
@Component
public class GroceryHttp {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";
    private static final Set<Integer> BLOCKED_STATUS = Set.of(403, 429, 503);

    private final HttpTransport transport;
    private final Duration timeout;

    @Autowired
    public GroceryHttp(GroceryProperties properties) {
        this(new JdkTransport(properties.getHttpTimeoutMs()), properties.getHttpTimeoutMs());
    }

    /** Für Tests: eigener Transport. */
    public GroceryHttp(HttpTransport transport) {
        this(transport, 20_000);
    }

    private GroceryHttp(HttpTransport transport, int timeoutMs) {
        this.transport = transport;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public String get(URI uri, Map<String, String> headers) throws SourceFetchException {
        return execute(builder(uri, headers).GET().build());
    }

    public String postJson(URI uri, String body, Map<String, String> headers) throws SourceFetchException {
        return execute(builder(uri, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpRequest.Builder builder(URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT);
        headers.forEach(builder::header);
        return builder;
    }

    private String execute(HttpRequest request) throws SourceFetchException {
        HttpTransport.HttpResult result;
        try {
            result = transport.send(request);
        } catch (IOException e) {
            throw new SourceFetchException("Verbindungsfehler: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceFetchException("Abruf unterbrochen", e);
        }
        boolean cloudflareChallenge = result.headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("Cf-Mitigated"));
        if (BLOCKED_STATUS.contains(result.status()) || cloudflareChallenge) {
            throw new HttpStatusException("Zugriff gesperrt (HTTP " + result.status() + ")", result.status());
        }
        if (result.status() >= 400) {
            throw new HttpStatusException("HTTP " + result.status(), result.status());
        }
        return result.body();
    }

    /** Produktiv-Transport auf Basis des JDK-HttpClient. */
    private static final class JdkTransport implements HttpTransport {

        private final HttpClient client;

        private JdkTransport(int timeoutMs) {
            this.client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
        }

        @Override
        public HttpResult send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.headers().map(), response.body());
        }
    }
}
