package com.household.manager.tapo;

import java.net.http.HttpHeaders;

final class TapoSessionCookie {

    private TapoSessionCookie() {
    }

    static String extract(HttpHeaders headers) {
        // Try TP_SESSIONID first (standard), then fall back to any Set-Cookie value
        return headers.allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith("TP_SESSIONID="))
                .findFirst()
                .or(() -> headers.allValues("Set-Cookie").stream()
                        .map(value -> value.split(";", 2)[0])
                        .filter(value -> !value.isBlank())
                        .findFirst())
                .orElseThrow(() -> new TapoException("Tapo-Session-Cookie TP_SESSIONID fehlt."));
    }
}
