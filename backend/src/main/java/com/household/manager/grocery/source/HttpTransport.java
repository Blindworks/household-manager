package com.household.manager.grocery.source;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/** Austauschbare Netzwerkschicht von {@link GroceryHttp}; Tests ersetzen sie durch Fixtures. */
public interface HttpTransport {

    HttpResult send(HttpRequest request) throws IOException, InterruptedException;

    record HttpResult(int status, Map<String, List<String>> headers, String body) {
    }
}
