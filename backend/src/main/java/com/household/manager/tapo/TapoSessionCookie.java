package com.household.manager.tapo;

import java.net.http.HttpHeaders;

final class TapoSessionCookie {

    private TapoSessionCookie() {
    }

    static String extract(HttpHeaders headers) {
        return headers.allValues("Set-Cookie").stream()
                .map(value -> value.split(";", 2)[0])
                .filter(value -> value.startsWith("TP_SESSIONID="))
                .findFirst()
                .orElseThrow(() -> new TapoException("Tapo-Session-Cookie TP_SESSIONID fehlt."));
    }
}
