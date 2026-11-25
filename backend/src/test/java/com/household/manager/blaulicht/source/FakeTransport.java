package com.household.manager.blaulicht.source;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Liefert feste Antworten je URL, alles andere 404; merkt sich die Anfragen. */
class FakeTransport implements BlaulichtHttp.Transport {

    final Map<String, String> bodies = new HashMap<>();
    final List<HttpRequest> requests = new ArrayList<>();
    String etag;

    @Override
    public BlaulichtHttp.Response send(HttpRequest request) {
        requests.add(request);
        if (etag != null && etag.equals(request.headers().firstValue("If-None-Match").orElse(null))) {
            return new BlaulichtHttp.Response(304, "", Optional.of(etag), Optional.empty());
        }
        String body = bodies.get(request.uri().toString());
        if (body == null) {
            return new BlaulichtHttp.Response(404, "", Optional.empty(), Optional.empty());
        }
        return new BlaulichtHttp.Response(200, body, Optional.ofNullable(etag), Optional.empty());
    }

    long count(String url) {
        return requests.stream().filter(r -> r.uri().toString().equals(url)).count();
    }
}
