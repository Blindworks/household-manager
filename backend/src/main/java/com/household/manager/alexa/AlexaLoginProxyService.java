package com.household.manager.alexa;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Eingebetteter Reverse-Proxy fuer den Amazon-Browser-Login.
 * <p>
 * Der Nutzer oeffnet die vom {@link #start()} gelieferte lokale URL im echten Browser und
 * meldet sich auf der (durchgereichten) echten Amazon-Seite an. Weil ein echter Browser
 * Amazons JavaScript ausfuehrt (u. a. das Betrugserkennungs-Feld {@code metadata1}), stellt
 * Amazon den OAuth2-Authorization-Code aus. Der Proxy faengt die Weiterleitung auf
 * {@code /ap/maplanding?...openid.oa2.authorization_code=...} ab und uebergibt den Code an
 * {@link AlexaAuthService#completeProxyRegistration}.
 * <p>
 * Der Proxy schreibt {@code www.amazon.<domain>} bidirektional auf den Proxy-Host um:
 * Antworten (Location, Body, Set-Cookie) Amazon -&gt; Proxy, Anfragen (URL, Body, Referer)
 * Proxy -&gt; Amazon. So bleibt der komplette Login auf dem Proxy-Origin, waehrend Amazon
 * ausschliesslich eigene URLs sieht.
 */
@Service
@Slf4j
public class AlexaLoginProxyService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AlexaAuthService authService;
    private final AlexaProperties properties;
    private final HttpClient httpClient;

    /** Gemeinsamer Cookie-Jar des laufenden Login-Vorgangs (lokaler Einzelnutzer-Flow). */
    private final Map<String, String> cookieJar = new ConcurrentHashMap<>();

    private volatile HttpServer server;
    private volatile AlexaAuthService.ProxyLoginContext context;
    private volatile String lastError;

    public AlexaLoginProxyService(AlexaAuthService authService, AlexaProperties properties) {
        this.authService = authService;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Startet einen frischen Login-Vorgang und liefert die lokale URL, die der Nutzer im
     * Browser oeffnen soll.
     */
    public synchronized String start() {
        stop();
        lastError = null;
        cookieJar.clear();

        context = authService.beginProxyLogin();
        // Seed-Cookies, die Amazons Geraeteregistrierung erwartet.
        cookieJar.put("frc", context.getFrc());
        cookieJar.put("map-md", context.getMapMd());

        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(properties.getProxy().getHost(), properties.getProxy().getPort()), 0);
            httpServer.createContext("/", this::handle);
            httpServer.setExecutor(Executors.newFixedThreadPool(8));
            httpServer.start();
            this.server = httpServer;
        } catch (IOException ex) {
            throw new AlexaException("Login-Proxy konnte nicht gestartet werden (Port belegt?): " + ex.getMessage(), ex);
        }

        String startUrl = proxyBase() + pathAndQuery(context.getAuthorizeUrl());
        log.info("Alexa-Login-Proxy gestartet, Startadresse: {}", startUrl);
        return startUrl;
    }

    /** Stoppt den Proxy-Server, falls er laeuft. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            log.info("Alexa-Login-Proxy gestoppt.");
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    /** Letzte Fehlermeldung eines Login-Versuchs (fuer die UI), sonst null. */
    public String getLastError() {
        return lastError;
    }

    // ==================== Request-Handling ====================

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            String amazonUrl = amazonHttpsBase() + path + (query != null ? "?" + toAmazon(query) : "");

            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            mergeBrowserCookies(exchange);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(amazonUrl))
                    .timeout(TIMEOUT)
                    .header("Cookie", serializeCookies(cookieJar))
                    .header("Accept-Encoding", "identity");
            copyRequestHeaders(exchange, builder);

            String method = exchange.getRequestMethod();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                byte[] outBody = rewriteRequestBody(exchange, requestBody);
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(outBody));
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            storeCookies(response.headers().allValues("set-cookie"));

            // Erfolg: Amazon hat den Browser auf /ap/maplanding mit dem Code geleitet.
            if (path.equals("/ap/maplanding") && query != null) {
                String code = queryParam(query, "openid.oa2.authorization_code");
                if (code != null && !code.isBlank()) {
                    handleAuthorizationCode(exchange, code);
                    return;
                }
            }

            writeProxiedResponse(exchange, response);
        } catch (Exception ex) {
            log.warn("Alexa-Login-Proxy Fehler bei {}: {}", exchange.getRequestURI(), ex.getMessage());
            sendHtml(exchange, 502, errorPage("Proxy-Fehler: " + ex.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleAuthorizationCode(HttpExchange exchange, String code) throws IOException {
        try {
            authService.completeProxyRegistration(code, context, serializeCookies(cookieJar));
            lastError = null;
            sendHtml(exchange, 200, successPage());
            log.info("Alexa-Browser-Login erfolgreich abgeschlossen.");
        } catch (Exception ex) {
            lastError = ex.getMessage();
            log.warn("Alexa-Geraeteregistrierung nach Browser-Login fehlgeschlagen: {}", ex.getMessage());
            sendHtml(exchange, 502, errorPage("Registrierung fehlgeschlagen: " + ex.getMessage()));
        } finally {
            // Login abgeschlossen (erfolgreich oder Fehler) -> Proxy in eigenem Thread stoppen.
            Thread stopper = new Thread(this::stop, "alexa-proxy-stop");
            stopper.setDaemon(true);
            stopper.start();
        }
    }

    private void writeProxiedResponse(HttpExchange exchange, HttpResponse<byte[]> response) throws IOException {
        String contentType = response.headers().firstValue("content-type").orElse("");
        byte[] body = response.body();
        if (isTextual(contentType) && body != null) {
            body = toBrowser(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }

        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (isSkippedResponseHeader(name)) {
                continue;
            }
            if (name.equals("location")) {
                exchange.getResponseHeaders().add("Location", toBrowser(header.getValue().get(0)));
            } else if (name.equals("set-cookie")) {
                for (String cookie : header.getValue()) {
                    exchange.getResponseHeaders().add("Set-Cookie", rewriteSetCookieForBrowser(cookie));
                }
            } else {
                for (String value : header.getValue()) {
                    exchange.getResponseHeaders().add(header.getKey(), value);
                }
            }
        }

        exchange.sendResponseHeaders(response.statusCode(), body == null ? -1 : body.length);
        if (body != null && body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    // ==================== Header-/Cookie-Handling ====================

    private void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder builder) {
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (isSkippedRequestHeader(lower)) {
                return;
            }
            String value = values.get(0);
            if (lower.equals("referer") || lower.equals("origin")) {
                value = toAmazon(value);
            }
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException ignored) {
                // Vom HttpClient eingeschraenkte Header ueberspringen.
            }
        });
    }

    private void mergeBrowserCookies(HttpExchange exchange) {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return;
        }
        for (String header : cookieHeaders) {
            for (String part : header.split(";\\s*")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    cookieJar.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
        }
    }

    private void storeCookies(List<String> setCookieHeaders) {
        for (String setCookie : setCookieHeaders) {
            int eq = setCookie.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            int semi = setCookie.indexOf(';');
            String name = setCookie.substring(0, eq).trim();
            String value = semi > eq ? setCookie.substring(eq + 1, semi).trim() : setCookie.substring(eq + 1).trim();
            if (value.isEmpty() || "\"\"".equals(value)) {
                continue;
            }
            cookieJar.put(name, value);
        }
    }

    /** Entfernt Domain/Secure/SameSite, damit der Cookie an den Proxy-Host (HTTP) bindet. */
    private String rewriteSetCookieForBrowser(String setCookie) {
        StringBuilder sb = new StringBuilder();
        for (String attr : setCookie.split(";\\s*")) {
            String lower = attr.toLowerCase(Locale.ROOT);
            if (lower.startsWith("domain=") || lower.equals("secure") || lower.startsWith("samesite=")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(attr);
        }
        return sb.toString();
    }

    // ==================== URL-Umschreibung ====================

    private String proxyBase() {
        return "http://" + properties.getProxy().getHost() + ":" + properties.getProxy().getPort();
    }

    private String proxyHostPort() {
        return properties.getProxy().getHost() + ":" + properties.getProxy().getPort();
    }

    private String amazonWww() {
        return "www." + properties.getDomain();
    }

    private String amazonHttpsBase() {
        return "https://www." + properties.getDomain();
    }

    /** Amazon -&gt; Proxy (fuer Antworten an den Browser). */
    private String toBrowser(String s) {
        if (s == null) {
            return null;
        }
        return s.replace(amazonHttpsBase(), proxyBase())
                .replace("http://" + amazonWww(), proxyBase())
                .replace("//" + amazonWww(), "//" + proxyHostPort())
                .replace(enc(amazonHttpsBase()), enc(proxyBase()));
    }

    /** Proxy -&gt; Amazon (fuer Anfragen/Formulardaten Richtung Amazon). */
    private String toAmazon(String s) {
        if (s == null) {
            return null;
        }
        return s.replace(proxyBase(), amazonHttpsBase())
                .replace("//" + proxyHostPort(), "//" + amazonWww())
                .replace(enc(proxyBase()), enc(amazonHttpsBase()));
    }

    private byte[] rewriteRequestBody(HttpExchange exchange, byte[] requestBody) {
        String contentType = firstHeader(exchange, "Content-Type");
        if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
            return toAmazon(new String(requestBody, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        }
        return requestBody;
    }

    // ==================== Hilfen ====================

    private static boolean isTextual(String contentType) {
        String c = contentType.toLowerCase(Locale.ROOT);
        return c.contains("text/html") || c.contains("text/css") || c.contains("javascript")
                || c.contains("application/json") || c.contains("text/plain") || c.contains("text/javascript");
    }

    private static boolean isSkippedRequestHeader(String lower) {
        return lower.equals("host") || lower.equals("cookie") || lower.equals("accept-encoding")
                || lower.equals("content-length") || lower.equals("connection")
                || lower.startsWith("proxy-") || lower.equals("upgrade-insecure-requests");
    }

    private static boolean isSkippedResponseHeader(String lower) {
        return lower.equals("content-encoding") || lower.equals("content-length")
                || lower.equals("transfer-encoding") || lower.equals("connection")
                || lower.startsWith(":");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String pathAndQuery(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    private static String serializeCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new LinkedHashMap<>(cookies).entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String successPage() {
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>Angemeldet</title></head>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">"
                + "<h1>Anmeldung erfolgreich</h1>"
                + "<p>Du kannst dieses Fenster schliessen und zum Household Manager zurueckkehren.</p>"
                + "</body></html>";
    }

    private static String errorPage(String message) {
        return "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>Fehler</title></head>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">"
                + "<h1>Anmeldung fehlgeschlagen</h1><p>" + escapeHtml(message) + "</p>"
                + "<p>Bitte im Household Manager erneut &quot;Login starten&quot; waehlen.</p>"
                + "</body></html>";
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
