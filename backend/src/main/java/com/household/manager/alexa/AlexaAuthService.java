package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.model.entity.AlexaAccount;
import com.household.manager.repository.AlexaAccountRepository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kapselt den inoffiziellen Amazon-Login (Geraeteregistrierung) sowie die
 * Verwaltung von Refresh-/Access-Token und alexa.<domain>-Cookies.
 * <p>
 * Der gesamte Amazon-spezifische, bruechige Code lebt hier und in {@link AlexaApiClient}.
 * Netzwerkaufrufe werden nicht unit-getestet; die Verifikation erfolgt manuell.
 * <p>
 * Portierung basiert auf der inoffiziellen Referenzimplementierung
 * <a href="https://github.com/Apollon77/alexa-cookie">Apollon77/alexa-cookie</a>
 * (alexa-cookie.js + lib/proxy.js), angepasst auf einen reinen PKCE/Authorization-Code-Flow
 * ohne lokalen Browser-Proxy.
 */
@Service
@Slf4j
public class AlexaAuthService {

    /** Ergebnisstatus eines Login-Schritts. */
    public enum LoginResult { OK, MFA_REQUIRED, CAPTCHA_REQUIRED, FAILED }

    /** Antwort eines Login-/MFA-Aufrufs an die UI. */
    @Getter
    public static class LoginStep {
        private final LoginResult result;
        private final String captchaImageUrl;
        private final String message;

        public LoginStep(LoginResult result, String captchaImageUrl, String message) {
            this.result = result;
            this.captchaImageUrl = captchaImageUrl;
            this.message = message;
        }
    }

    private static final Duration SESSION_TTL = Duration.ofHours(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** iOS-App-Kennwerte, wie sie die offizielle Alexa-iOS-App verwendet (Stand alexa-cookie). */
    private static final String DEVICE_TYPE = "A2IVLV5VM2W81";
    private static final String API_CALL_VERSION = "2.2.651540.0";
    private static final String API_USER_AGENT = "AmazonWebView/Amazon Alexa/2.2.651540.0/iOS/18.3.1/iPhone";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36";
    private static final String ACCEPT_LANGUAGE = "de-DE";
    private static final String DEVICE_APP_NAME = "Household Manager Alexa";
    private static final int MAX_REDIRECTS = 10;

    /** Amazon-Cookienamen, die bei der Geraeteregistrierung mitgeschickt werden duerfen. */
    private static final Pattern AMAZON_COOKIE_NAME = Pattern.compile(
            "^(session-id|session-id-time|session-token|ubid-.+|lc-.+|x-.+|at-.+|sess-at-.+|frc|map-md|csrf|sid|csm-hit|i18n-prefs|sp-cdn|skin)$");

    private static final Pattern HIDDEN_FIELDS_START = Pattern.compile("(?s)\"hidden\"\\s*name=\".*");
    private static final Pattern FIELD_PATTERN = Pattern.compile("name=\"([^\"]+)\"[^>]*?value=\"([^\"]*)\"");
    private static final Pattern ERROR_BOX = Pattern.compile(
            "(?s)id=\"auth-(?:warning|error)-message-box\".{0,3000}?<h4[^>]*>(.*?)</h4>");
    private static final Pattern ERROR_LIST_ITEM = Pattern.compile("(?s)<li[^>]*>(.*?)</li>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern CAPTCHA_IMAGE_A = Pattern.compile("id=\"auth-captcha-image\"[^>]*src=\"([^\"]+)\"");
    private static final Pattern CAPTCHA_IMAGE_B = Pattern.compile("src=\"([^\"]+)\"[^>]*id=\"auth-captcha-image\"");

    private static final String[] CSRF_PATHS = {
            "/api/language",
            "/spa/index.html",
            "/api/devices-v2/device?cached=false",
            "/templates/oobe/d-device-pick.handlebars",
            "/api/strings"
    };

    private final AlexaAccountRepository accountRepository;
    private final AlexaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    /** Laufender Login-Flow-Zustand zwischen /login und /mfa (nur ein Flow gleichzeitig). */
    private volatile PendingLogin pendingLogin;

    /** Gecachte, gueltige Sitzung. */
    private volatile AlexaSession session;

    /** true, wenn Refresh endgueltig fehlschlug und Neuanmeldung noetig ist. */
    @Getter
    private volatile boolean reauthRequired;

    public AlexaAuthService(AlexaAccountRepository accountRepository,
                            AlexaProperties properties,
                            ObjectMapper mapper) {
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Zwischenzustand des laufenden Login-Flows. */
    private static final class PendingLogin {
        String sessionCookies;
        String signInReferer;
        String codeVerifier;
        String deviceSerial;
        String frc;
        String mapMd;
        // weitere versteckte Felder je nach Amazon-Formular
        String email;
        Map<String, String> lastFormFields;
    }

    /** Ergebnis eines HTTP-Aufrufs inkl. manuell verfolgter Redirects. */
    private record FollowResult(HttpResponse<String> response, String authorizationCode) {
    }

    /** Cookie-Header-Wert zusammen mit dem daraus gelesenen CSRF-Token. */
    private record CookieAndCsrf(String cookie, String csrf) {
    }

    /** Signalisiert, dass Amazon das Refresh-Token endgueltig abgelehnt hat (Neuanmeldung noetig). */
    private static class RefreshTokenRejectedException extends RuntimeException {
        RefreshTokenRejectedException(String message) {
            super(message);
        }
    }

    // ==================== Public API ====================

    public synchronized LoginStep login(String email, String password, String captchaSolution) {
        reauthRequired = false;
        try {
            PendingLogin pl = new PendingLogin();
            pl.email = email;
            pl.deviceSerial = randomHexUpper(16);
            pl.codeVerifier = generateCodeVerifier();
            String codeChallenge = codeChallengeFor(pl.codeVerifier);
            pl.frc = randomBase64Bytes(313);
            pl.mapMd = buildMapMd();
            pl.sessionCookies = "frc=" + pl.frc + "; map-md=" + pl.mapMd;

            // 1) GET alexa.<domain>/  -> Basis-Cookies
            FollowResult baseStep = sendFollowingRedirects(
                    browserGetRequest(alexaHost() + "/", pl.sessionCookies), pl);
            if (baseStep.authorizationCode() != null) {
                return finishWithAuthorizationCode(pl, baseStep.authorizationCode());
            }

            // 2) GET /ap/signin mit OAuth/PKCE-Parametern -> Formularfelder
            String clientId = buildClientId(pl.deviceSerial);
            String initialUrl = buildInitialSigninUrl(clientId, codeChallenge);
            FollowResult signinStep = sendFollowingRedirects(
                    browserGetRequest(initialUrl, pl.sessionCookies), pl);
            if (signinStep.authorizationCode() != null) {
                return finishWithAuthorizationCode(pl, signinStep.authorizationCode());
            }

            Map<String, String> fields = parseHiddenFields(signinStep.response().body());
            String sessionId = extractCookieValue(pl.sessionCookies, "session-id");
            pl.signInReferer = sessionId != null
                    ? wwwHost() + "/ap/signin/" + sessionId
                    : initialUrl;

            fields.put("email", email == null ? "" : email);
            fields.put("password", password == null ? "" : password);
            if (captchaSolution != null && !captchaSolution.isBlank()) {
                fields.put("guess", captchaSolution);
            }

            // 3) POST /ap/signin (email/password [+ captchaSolution]) -> HTML auswerten
            FollowResult credStep = sendFollowingRedirects(
                    signinPostRequest(fields, pl.sessionCookies, pl.signInReferer), pl);

            return handleSigninResponse(pl, credStep);
        } catch (Exception ex) {
            log.warn("Alexa-Login fehlgeschlagen: {}", ex.getMessage());
            pendingLogin = null;
            return new LoginStep(LoginResult.FAILED, null, "Login fehlgeschlagen: " + ex.getMessage());
        }
    }

    public synchronized LoginStep submitMfa(String code) {
        if (pendingLogin == null) {
            return new LoginStep(LoginResult.FAILED, null, "Kein laufender Login-Vorgang.");
        }
        PendingLogin pl = pendingLogin;
        try {
            Map<String, String> fields = pl.lastFormFields != null
                    ? new LinkedHashMap<>(pl.lastFormFields)
                    : new LinkedHashMap<>();
            fields.put("otpCode", code == null ? "" : code);
            fields.put("rememberDevice", "false");
            String referer = pl.signInReferer != null ? pl.signInReferer : wwwHost() + "/ap/signin";

            FollowResult result = sendFollowingRedirects(
                    signinPostRequest(fields, pl.sessionCookies, referer), pl);

            return handleSigninResponse(pl, result);
        } catch (Exception ex) {
            log.warn("Alexa-MFA fehlgeschlagen: {}", ex.getMessage());
            pendingLogin = null;
            return new LoginStep(LoginResult.FAILED, null, "MFA fehlgeschlagen: " + ex.getMessage());
        }
    }

    private LoginStep handleSigninResponse(PendingLogin pl, FollowResult result) {
        if (result.authorizationCode() != null) {
            return finishWithAuthorizationCode(pl, result.authorizationCode());
        }
        String body = result.response().body();
        if (isMfaPage(body)) {
            pl.lastFormFields = parseHiddenFields(body);
            pendingLogin = pl;
            return new LoginStep(LoginResult.MFA_REQUIRED, null, "Bestaetigungscode (MFA) erforderlich.");
        }
        if (isCaptchaPage(body)) {
            pl.lastFormFields = parseHiddenFields(body);
            pendingLogin = pl;
            return new LoginStep(LoginResult.CAPTCHA_REQUIRED, extractCaptchaImageUrl(body), "Captcha erforderlich.");
        }
        pendingLogin = null;
        String message = extractErrorMessage(body);
        return new LoginStep(LoginResult.FAILED, null,
                message != null ? message : "Login fehlgeschlagen. Bitte Zugangsdaten pruefen.");
    }

    private LoginStep finishWithAuthorizationCode(PendingLogin pl, String authorizationCode) {
        pendingLogin = pl;
        completeRegistration(authorizationCode);
        return new LoginStep(LoginResult.OK, null, null);
    }

    /** Nach erfolgreichem Signin: /auth/register aufrufen, refresh_token speichern. */
    private void completeRegistration(String authorizationCode) {
        PendingLogin pl = this.pendingLogin;
        if (pl == null) {
            throw new AlexaException("Kein laufender Login-Vorgang fuer Registrierung.");
        }
        try {
            Map<String, String> sanitizedCookies = sanitizeAmazonCookies(parseCookieHeader(pl.sessionCookies));
            String sanitizedCookieHeader = serializeCookies(sanitizedCookies);
            String clientId = buildClientId(pl.deviceSerial);

            ObjectNode registerData = mapper.createObjectNode();
            registerData.putArray("requested_extensions").add("device_info").add("customer_info");

            ObjectNode cookiesNode = registerData.putObject("cookies");
            ArrayNode websiteCookies = cookiesNode.putArray("website_cookies");
            for (Map.Entry<String, String> e : sanitizedCookies.entrySet()) {
                ObjectNode cookieNode = websiteCookies.addObject();
                cookieNode.put("Value", e.getValue());
                cookieNode.put("Name", e.getKey());
            }
            cookiesNode.put("domain", "." + properties.getDomain());

            ObjectNode registrationData = registerData.putObject("registration_data");
            registrationData.put("domain", "Device");
            registrationData.put("app_version", API_CALL_VERSION);
            registrationData.put("device_type", DEVICE_TYPE);
            registrationData.put("device_name", "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%" + DEVICE_APP_NAME);
            registrationData.put("os_version", "18.3.1");
            registrationData.put("device_serial", pl.deviceSerial);
            registrationData.put("device_model", "iPhone");
            registrationData.put("app_name", DEVICE_APP_NAME);
            registrationData.put("software_version", "1");

            ObjectNode authData = registerData.putObject("auth_data");
            authData.put("client_id", clientId);
            authData.put("authorization_code", authorizationCode);
            authData.put("code_verifier", pl.codeVerifier);
            authData.put("code_algorithm", "SHA-256");
            authData.put("client_domain", "DeviceLegacy");

            ObjectNode userContextMap = registerData.putObject("user_context_map");
            userContextMap.put("frc", pl.frc);

            ArrayNode requestedTokenType = registerData.putArray("requested_token_type");
            requestedTokenType.add("bearer");
            requestedTokenType.add("mac_dms");
            requestedTokenType.add("website_cookies");

            HttpRequest request = HttpRequest.newBuilder(URI.create(apiHost() + "/auth/register"))
                    .timeout(TIMEOUT)
                    .header("User-Agent", API_USER_AGENT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Accept-Charset", "utf-8")
                    .header("Content-Type", "application/json")
                    .header("Cookie", sanitizedCookieHeader)
                    .header("Accept", "application/json")
                    .header("x-amzn-identity-auth-domain", "api." + properties.getDomain())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(registerData)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new AlexaException("Alexa /auth/register HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode success = root.path("response").path("success");
            String refreshToken = success.path("tokens").path("bearer").path("refresh_token").asText(null);
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new AlexaException("Keine refresh_token in /auth/register-Antwort: " + response.body());
            }
            String accountName = success.path("extensions").path("customer_info").path("name").asText(null);
            if (accountName == null || accountName.isBlank()) {
                accountName = pl.email;
            }
            saveRefreshToken(refreshToken, accountName);
            session = buildSessionFromRefreshToken(refreshToken);
            reauthRequired = false;
        } catch (AlexaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AlexaException("Amazon-Geraeteregistrierung fehlgeschlagen.", ex);
        } finally {
            pendingLogin = null;
        }
    }

    public synchronized void logout() {
        accountRepository.findFirstByOrderByIdAsc().ifPresent(accountRepository::delete);
        session = null;
        pendingLogin = null;
        reauthRequired = false;
    }

    public boolean isLoggedIn() {
        return accountRepository.findFirstByOrderByIdAsc().isPresent();
    }

    public String getAccountName() {
        return accountRepository.findFirstByOrderByIdAsc()
                .map(AlexaAccount::getAccountName)
                .orElse(null);
    }

    /**
     * Liefert eine gueltige Sitzung; erneuert sie bei Ablauf per Refresh-Token.
     * Wirft {@link AlexaException}, wenn nicht angemeldet oder Refresh endgueltig scheitert.
     */
    public synchronized AlexaSession getValidSession() {
        if (session != null && session.isValid()) {
            return session;
        }
        AlexaAccount account = accountRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new AlexaException("Nicht bei Amazon angemeldet."));
        try {
            session = buildSessionFromRefreshToken(account.getRefreshToken());
            reauthRequired = false;
            return session;
        } catch (RefreshTokenRejectedException ex) {
            reauthRequired = true;
            log.warn("Alexa Refresh-Token endgueltig abgelehnt, Neuanmeldung erforderlich: {}", ex.getMessage());
            throw new AlexaException("Alexa-Sitzung abgelaufen, Neuanmeldung erforderlich.", ex);
        } catch (Exception ex) {
            log.warn("Alexa-Sitzung konnte voruebergehend nicht erneuert werden: {}", ex.getMessage());
            throw new AlexaException("Alexa-Sitzung konnte voruebergehend nicht erneuert werden.", ex);
        }
    }

    private AlexaSession buildSessionFromRefreshToken(String refreshToken) {
        try {
            // 1) /auth/token -> access_token
            String accessToken = fetchAccessToken(refreshToken);
            // 2) /ap/exchangetoken/cookies -> website cookies
            String exchangedCookies = exchangeRefreshTokenForCookies(refreshToken);
            // 3) GET /api/language (o.ae.) -> csrf
            CookieAndCsrf csrfResult = fetchCsrf(exchangedCookies);
            if (csrfResult.csrf() == null) {
                throw new AlexaException("Kein CSRF-Token von Amazon erhalten.");
            }
            // 4) GET /api/users/me -> customerId
            String customerId = fetchCustomerId(csrfResult.cookie());
            return AlexaSession.builder()
                    .cookie(csrfResult.cookie())
                    .csrf(csrfResult.csrf())
                    .accessToken(accessToken)
                    .customerId(customerId)
                    .expiresAt(Instant.now().plus(SESSION_TTL))
                    .build();
        } catch (RefreshTokenRejectedException ex) {
            throw ex;
        } catch (AlexaException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Sitzung konnte nicht aus Refresh-Token aufgebaut werden.", ex);
        }
    }

    private void saveRefreshToken(String refreshToken, String accountName) {
        AlexaAccount account = accountRepository.findFirstByOrderByIdAsc()
                .orElseGet(AlexaAccount::new);
        account.setRefreshToken(refreshToken);
        account.setAmazonDomain(properties.getDomain());
        account.setAccountName(accountName);
        accountRepository.save(account);
    }

    // ==================== Refresh-Token -> Sitzung ====================

    private String fetchAccessToken(String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("app_name", DEVICE_APP_NAME);
        form.put("app_version", API_CALL_VERSION);
        form.put("di.sdk.version", "6.12.4");
        form.put("source_token", refreshToken);
        form.put("package_name", "com.amazon.echo");
        form.put("di.hw.version", "iPhone");
        form.put("platform", "iOS");
        form.put("requested_token_type", "access_token");
        form.put("source_token_type", "refresh_token");
        form.put("di.os.name", "iOS");
        form.put("di.os.version", "16.6");
        form.put("current_version", "6.12.4");

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiHost() + "/auth/token"))
                .timeout(TIMEOUT)
                .header("User-Agent", API_USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Accept-Charset", "utf-8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("x-amzn-identity-auth-domain", "api." + properties.getDomain())
                .POST(HttpRequest.BodyPublishers.ofString(toFormBody(form)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            // Amazon lehnt ein ungueltiges/abgelaufenes Refresh-Token mit 400 oder 401 ab -> endgueltig, Neuanmeldung noetig.
            throw new RefreshTokenRejectedException(
                    "Alexa /auth/token HTTP " + response.statusCode() + ": " + response.body());
        }
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa /auth/token HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        String accessToken = root.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new AlexaException("Kein access_token in /auth/token-Antwort.");
        }
        return accessToken;
    }

    private String exchangeRefreshTokenForCookies(String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("di.os.name", "iOS");
        form.put("app_version", API_CALL_VERSION);
        form.put("domain", "." + properties.getDomain());
        form.put("source_token", refreshToken);
        form.put("requested_token_type", "auth_cookies");
        form.put("source_token_type", "refresh_token");
        form.put("di.hw.version", "iPhone");
        form.put("di.sdk.version", "6.12.4");
        form.put("app_name", DEVICE_APP_NAME);
        form.put("di.os.version", "16.6");

        HttpRequest request = HttpRequest.newBuilder(URI.create(wwwHost() + "/ap/exchangetoken/cookies"))
                .timeout(TIMEOUT)
                .header("User-Agent", API_USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Accept-Charset", "utf-8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "*/*")
                .header("x-amzn-identity-auth-domain", "api." + properties.getDomain())
                .POST(HttpRequest.BodyPublishers.ofString(toFormBody(form)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa /ap/exchangetoken/cookies HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode cookiesNode = root.path("response").path("tokens").path("cookies").path("." + properties.getDomain());
        if (!cookiesNode.isArray() || cookiesNode.isEmpty()) {
            throw new AlexaException("Keine Website-Cookies in /ap/exchangetoken/cookies-Antwort.");
        }
        Map<String, String> cookies = mergeCookiesFromHeaders(new LinkedHashMap<>(), response.headers().allValues("set-cookie"));
        for (JsonNode c : cookiesNode) {
            cookies.put(c.path("Name").asText(), c.path("Value").asText());
        }
        return serializeCookies(cookies);
    }

    private CookieAndCsrf fetchCsrf(String cookieHeader) throws Exception {
        Map<String, String> cookies = parseCookieHeader(cookieHeader);
        for (String path : CSRF_PATHS) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(alexaHost() + path))
                    .timeout(TIMEOUT)
                    .header("DNT", "1")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Referer", alexaHost() + "/spa/index.html")
                    .header("Cookie", serializeCookies(cookies))
                    .header("Accept", "*/*")
                    .header("Origin", alexaHost())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            mergeCookiesFromHeaders(cookies, response.headers().allValues("set-cookie"));
            String csrf = cookies.get("csrf");
            if (csrf != null && !csrf.isBlank()) {
                return new CookieAndCsrf(serializeCookies(cookies), csrf);
            }
        }
        return new CookieAndCsrf(serializeCookies(cookies), null);
    }

    private String fetchCustomerId(String cookieHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(alexaHost() + "/api/users/me"))
                .timeout(TIMEOUT)
                .header("User-Agent", API_USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Accept", "application/json")
                .header("Cookie", cookieHeader)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa /api/users/me HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        String id = root.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new AlexaException("Keine customerId in /api/users/me-Antwort.");
        }
        return id;
    }

    // ==================== Amazon-Hosts ====================

    private String alexaHost() {
        return "https://alexa." + properties.getDomain();
    }

    private String wwwHost() {
        return "https://www." + properties.getDomain();
    }

    private String apiHost() {
        return "https://api." + properties.getDomain();
    }

    // ==================== HTTP / Redirect-Handling ====================

    private HttpRequest browserGetRequest(String url, String cookies) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Accept", "*/*")
                .GET();
        if (cookies != null && !cookies.isBlank()) {
            builder.header("Cookie", cookies);
        }
        return builder.build();
    }

    private HttpRequest signinPostRequest(Map<String, String> fields, String cookies, String referer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(wwwHost() + "/ap/signin"))
                .timeout(TIMEOUT)
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referer)
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofString(toFormBody(fields)));
        if (cookies != null && !cookies.isBlank()) {
            builder.header("Cookie", cookies);
        }
        return builder.build();
    }

    /**
     * Sendet die Anfrage und folgt manuell allen Redirects (der HttpClient ist mit
     * {@code Redirect.NEVER} konfiguriert), da bei jedem Hop Cookies gesammelt werden muessen und
     * ein Redirect auf {@code openid.oa2.authorization_code=...} den erfolgreichen Loginabschluss markiert.
     */
    private FollowResult sendFollowingRedirects(HttpRequest initialRequest, PendingLogin pl) throws Exception {
        HttpRequest request = initialRequest;
        URI currentUri = initialRequest.uri();
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            pl.sessionCookies = mergeCookies(pl.sessionCookies, response.headers().allValues("set-cookie"));

            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return new FollowResult(response, null);
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null) {
                return new FollowResult(response, null);
            }
            URI resolved = currentUri.resolve(location);
            String code = extractQueryParam(resolved.toString(), "openid.oa2.authorization_code");
            if (code != null) {
                return new FollowResult(response, code);
            }
            currentUri = resolved;
            request = browserGetRequest(resolved.toString(), pl.sessionCookies);
        }
        throw new AlexaException("Zu viele Weiterleitungen beim Amazon-Login.");
    }

    // ==================== HTML-Parsing ====================

    private Map<String, String> parseHiddenFields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (html == null) {
            return fields;
        }
        String normalized = html.replaceAll("[\\n\\r]", " ");
        Matcher blockMatcher = HIDDEN_FIELDS_START.matcher(normalized);
        String block = blockMatcher.find() ? blockMatcher.group() : normalized;
        Matcher fieldMatcher = FIELD_PATTERN.matcher(block);
        while (fieldMatcher.find()) {
            String name = fieldMatcher.group(1);
            if (!"rememberMe".equals(name)) {
                fields.putIfAbsent(name, fieldMatcher.group(2));
            }
        }
        return fields;
    }

    private boolean isMfaPage(String html) {
        return html != null && (html.contains("auth-mfa-otpcode")
                || html.contains("name=\"otpCode\"")
                || html.contains("cvf-widget-input-code"));
    }

    private boolean isCaptchaPage(String html) {
        return html != null && html.contains("auth-captcha-image");
    }

    private String extractCaptchaImageUrl(String html) {
        Matcher matcher = CAPTCHA_IMAGE_A.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = CAPTCHA_IMAGE_B.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractErrorMessage(String html) {
        if (html == null) {
            return null;
        }
        Matcher box = ERROR_BOX.matcher(html);
        if (!box.find()) {
            return null;
        }
        String heading = HTML_TAG.matcher(box.group(1)).replaceAll("").trim();
        Matcher item = ERROR_LIST_ITEM.matcher(html.substring(box.end()));
        String detail = item.find() ? HTML_TAG.matcher(item.group(1)).replaceAll("").trim() : "";
        if (heading.isBlank() && detail.isBlank()) {
            return null;
        }
        return detail.isBlank() ? heading : heading + ": " + detail;
    }

    private String extractQueryParam(String url, String param) {
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        String query = url.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(param)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    // ==================== OAuth / PKCE / Geraete-Identitaet ====================

    private String signinHandle() {
        String domain = properties.getDomain();
        String tld = domain.substring(domain.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return "com".equals(tld) ? "" : "_" + tld;
    }

    private String buildInitialSigninUrl(String clientId, String codeChallenge) {
        String www = wwwHost();
        String handle = signinHandle();
        return www + "/ap/signin"
                + "?openid.return_to=" + enc(www + "/ap/maplanding")
                + "&openid.assoc_handle=" + enc("amzn_dp_project_dee_ios" + handle)
                + "&openid.identity=" + enc("http://specs.openid.net/auth/2.0/identifier_select")
                + "&pageId=" + enc("amzn_dp_project_dee_ios" + handle)
                + "&accountStatusPolicy=P1"
                + "&openid.claimed_id=" + enc("http://specs.openid.net/auth/2.0/identifier_select")
                + "&openid.mode=checkid_setup"
                + "&openid.ns.oa2=" + enc(www + "/ap/ext/oauth/2")
                + "&openid.oa2.client_id=" + enc("device:" + clientId)
                + "&openid.ns.pape=" + enc("http://specs.openid.net/extensions/pape/1.0")
                + "&openid.oa2.response_type=code"
                + "&openid.ns=" + enc("http://specs.openid.net/auth/2.0")
                + "&openid.pape.max_auth_age=0"
                + "&openid.oa2.scope=device_auth_access"
                + "&openid.oa2.code_challenge_method=S256"
                + "&openid.oa2.code_challenge=" + enc(codeChallenge)
                // Nur ein UI-Sprachhinweis fuer Amazon, unabhaengig von der Login-Domain (siehe alexa-cookie-Default).
                + "&language=de_DE";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomHexUpper(int byteLength) {
        byte[] buf = new byte[byteLength];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(byteLength * 2);
        for (byte b : buf) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String hexEncodeAscii(String ascii) {
        StringBuilder sb = new StringBuilder();
        for (byte b : ascii.getBytes(StandardCharsets.US_ASCII)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** client_id fuer den OAuth-Flow, abgeleitet aus deviceSerial + Geraetetyp (vgl. alexa-cookie lib/proxy.js). */
    private static String buildClientId(String deviceSerial) {
        return hexEncodeAscii(deviceSerial) + hexEncodeAscii("#" + DEVICE_TYPE);
    }

    private static String generateCodeVerifier() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String codeChallengeFor(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String randomBase64Bytes(int byteLength) {
        byte[] buf = new byte[byteLength];
        new SecureRandom().nextBytes(buf);
        return Base64.getEncoder().encodeToString(buf);
    }

    private static String buildMapMd() {
        String json = "{\"device_user_dictionary\":[],\"device_registration_data\":{\"software_version\":\"1\"},"
                + "\"app_identifier\":{\"app_version\":\"" + API_CALL_VERSION + "\",\"bundle_id\":\"com.amazon.echo\"}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== Cookie-Handling ====================

    private static Map<String, String> parseCookieHeader(String header) {
        Map<String, String> map = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            return map;
        }
        for (String part : header.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static Map<String, String> mergeCookiesFromHeaders(Map<String, String> cookies, java.util.List<String> setCookieHeaders) {
        for (String setCookie : setCookieHeaders) {
            int eq = setCookie.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            int semi = setCookie.indexOf(';');
            String name = setCookie.substring(0, eq).trim();
            String value = semi > eq ? setCookie.substring(eq + 1, semi).trim() : setCookie.substring(eq + 1).trim();
            if ("ap-fid".equals(name) && "\"\"".equals(value)) {
                continue;
            }
            cookies.put(name, value);
        }
        return cookies;
    }

    private static String serializeCookies(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String mergeCookies(String existingHeader, java.util.List<String> setCookieHeaders) {
        Map<String, String> cookies = parseCookieHeader(existingHeader);
        mergeCookiesFromHeaders(cookies, setCookieHeaders);
        return serializeCookies(cookies);
    }

    private static String extractCookieValue(String cookieHeader, String name) {
        return parseCookieHeader(cookieHeader).get(name);
    }

    private static Map<String, String> sanitizeAmazonCookies(Map<String, String> cookies) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (AMAZON_COOKIE_NAME.matcher(e.getKey()).matches()) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    private static String toFormBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
