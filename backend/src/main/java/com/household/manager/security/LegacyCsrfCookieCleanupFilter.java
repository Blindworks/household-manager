package com.household.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * Raeumt das XSRF-TOKEN-Altcookie mit Pfad /api ab, das eine fruehe Version
 * gesetzt hat. Solange es neben dem korrekten /-Cookie existiert, sendet der
 * Browser bei /api-Requests beide, der Server liest das Alt-Cookie zuerst und
 * jeder POST scheitert mit 403. Der Cookie-Header traegt keine Pfadinfo —
 * ein doppelter XSRF-TOKEN-Name ist das einzige serverseitig sichtbare
 * Signal fuer das Altcookie. Bewusst kein @Component (sonst wuerde Boot den
 * Filter zusaetzlich global registrieren); Registrierung nur in SecurityConfig.
 */
final class LegacyCsrfCookieCleanupFilter extends OncePerRequestFilter {

    static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    static final String LEGACY_PATH = "/api";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        if (cookies != null && Arrays.stream(cookies)
                .filter(cookie -> CSRF_COOKIE_NAME.equals(cookie.getName()))
                .count() > 1) {
            Cookie legacy = new Cookie(CSRF_COOKIE_NAME, "");
            legacy.setPath(LEGACY_PATH);
            legacy.setMaxAge(0);
            response.addCookie(legacy);
        }
        filterChain.doFilter(request, response);
    }
}
