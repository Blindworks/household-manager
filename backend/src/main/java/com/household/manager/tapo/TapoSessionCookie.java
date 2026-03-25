package com.household.manager.tapo;

import java.net.http.HttpHeaders;
import java.util.List;

final class TapoSessionCookie {

    private TapoSessionCookie() {
    }

    /**
     * Extracts the session cookie from a {@link HttpHeaders} instance (used by AES connection).
     */
    static String extract(HttpHeaders headers) {
        return extract(headers.allValues("Set-Cookie"));
    }

    /**
     * Extracts the session cookie from a raw list of Set-Cookie header values (used by KLAP socket connection).
     */
    static String extract(List<String> setCookieValues) {
        return setCookieValues.stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith("TP_SESSIONID="))
                .findFirst()
                .or(() -> setCookieValues.stream()
                        .map(value -> value.split(";", 2)[0])
                        .filter(value -> !value.isBlank())
                        .findFirst())
                .orElseThrow(() -> new TapoException("Tapo-Session-Cookie TP_SESSIONID fehlt."));
    }
}
