package com.household.manager.blaulicht.source;

import com.household.manager.blaulicht.BlaulichtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Höflicher HTTP-Zugriff der Blaulicht-Quellen: eigener User-Agent, HTTP/1.1, Timeout und bedingte
 * Abrufe. {@link #getIfChanged} merkt sich ETag/Last-Modified je URL (nur im Speicher) und liefert bei
 * 304 leer. Presseportal sendet beides heute nicht, der bedingte Abruf schadet dort aber nicht.
 */
@Component
public class BlaulichtHttp {

    /** Für Tests ersetzbar. */
    public interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record Response(int status, String body, Optional<String> etag, Optional<String> lastModified) {
    }

    private record Validators(String etag, String lastModified) {
    }

    private final Transport transport;
    private final String userAgent;
    private final Duration timeout;
    private final Map<String, Validators> validators = new ConcurrentHashMap<>();

    @Autowired
    public BlaulichtHttp(BlaulichtProperties properties) {
        this(jdkTransport(properties.getTimeoutMs()), properties.getUserAgent(), properties.getTimeoutMs());
    }

    public BlaulichtHttp(Transport transport, String userAgent, int timeoutMs) {
        this.transport = transport;
        this.userAgent = userAgent;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** Bedingter Abruf; leer bei 304 Not Modified. */
    public Optional<String> getIfChanged(String url) throws BlaulichtFetchException {
        HttpRequest.Builder builder = builder(url);
        Validators known = validators.get(url);
        if (known != null) {
            if (known.etag() != null) {
                builder.header("If-None-Match", known.etag());
            }
            if (known.lastModified() != null) {
                builder.header("If-Modified-Since", known.lastModified());
            }
        }
        Response response = execute(builder.GET().build());
        if (response.status() == 304) {
            return Optional.empty();
        }
        String etag = response.etag().orElse(null);
        String lastModified = response.lastModified().orElse(null);
        if (etag != null || lastModified != null) {
            validators.put(url, new Validators(etag, lastModified));
        }
        return Optional.of(response.body());
    }

    /** Einfacher Abruf (Detailseiten, die nur einmal gelesen werden). */
    public String get(String url) throws BlaulichtFetchException {
        return execute(builder(url).GET().build()).body();
    }

    /** Fehlt der Abruf, soll der nächste wieder vollständig sein (z. B. nach einem Parserfehler). */
    public void forget(String url) {
        validators.remove(url);
    }

    private HttpRequest.Builder builder(String url) throws BlaulichtFetchException {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "de");
        } catch (IllegalArgumentException e) {
            throw new BlaulichtFetchException("Ungültige URL: " + url, e);
        }
    }

    private Response execute(HttpRequest request) throws BlaulichtFetchException {
        Response response;
        try {
            response = transport.send(request);
        } catch (IOException e) {
            throw new BlaulichtFetchException("Verbindungsfehler: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BlaulichtFetchException("Abruf unterbrochen", e);
        }
        if (response.status() == 304) {
            return response;
        }
        if (response.status() >= 400) {
            throw new BlaulichtFetchException("HTTP " + response.status() + " von " + request.uri().getHost());
        }
        return response;
    }

    private static Transport jdkTransport(int timeoutMs) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        return request -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("ETag"), response.headers().firstValue("Last-Modified"));
        };
    }
}
