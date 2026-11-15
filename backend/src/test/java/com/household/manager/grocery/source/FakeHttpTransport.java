package com.household.manager.grocery.source;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-Transport: Antworten je URL-Präfix (längster Präfix gewinnt). Mehrere Antworten je Präfix werden der
 * Reihe nach geliefert, die letzte wiederholt sich. Alle Anfragen werden aufgezeichnet.
 */
public class FakeHttpTransport implements HttpTransport {

    private final Map<String, Deque<HttpResult>> responses = new LinkedHashMap<>();
    private final List<HttpRequest> requests = new ArrayList<>();

    public FakeHttpTransport respond(String urlPrefix, int status, String body) {
        return respond(urlPrefix, status, body, Map.of());
    }

    public FakeHttpTransport respond(String urlPrefix, int status, String body, Map<String, List<String>> headers) {
        responses.computeIfAbsent(urlPrefix, key -> new ArrayDeque<>()).add(new HttpResult(status, headers, body));
        return this;
    }

    public List<HttpRequest> requests() {
        return requests;
    }

    public List<String> requestedUrls() {
        return requests.stream().map(request -> request.uri().toString()).toList();
    }

    @Override
    public synchronized HttpResult send(HttpRequest request) throws IOException {
        requests.add(request);
        String url = request.uri().toString();
        String bestPrefix = null;
        for (String prefix : responses.keySet()) {
            if (url.startsWith(prefix) && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                bestPrefix = prefix;
            }
        }
        if (bestPrefix == null) {
            throw new IOException("Keine Fake-Antwort für " + url);
        }
        Deque<HttpResult> queue = responses.get(bestPrefix);
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }
}
