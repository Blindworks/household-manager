package com.household.manager.dirigera;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorResolver;
import com.household.manager.audit.AuditService;
import com.household.manager.dirigera.DirigeraPairingStatus.State;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.dirigera.client.Pkce;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Koppelt den Hub per PKCE: Code holen, auf den Knopfdruck am Hub warten, Token abholen.
 * Das Warten laeuft auf einem eigenen Thread, damit der HTTP-Request sofort zurueckkehrt.
 * Der Zustand liegt nur im Speicher; ein Neustart verwirft einen laufenden Vorgang.
 */
@Service
@Slf4j
public class DirigeraPairingService {

    static final Duration BUTTON_WINDOW = Duration.ofSeconds(90);
    static final long POLL_SECONDS = 2;
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_BODY_IN_ERROR = 200;

    private final DirigeraSettingsService settingsService;
    private final DirigeraHttpClient httpClient;
    private final DirigeraDeviceParser parser;
    private final DirigeraPollingService pollingService;
    private final AuditService auditService;
    private final AuditActorResolver actorResolver;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    // Alle Zustandsfelder nur unter "this"
    private DirigeraPairingStatus status = DirigeraPairingStatus.idle();
    private Attempt current;
    private boolean starting;

    @Autowired
    public DirigeraPairingService(DirigeraSettingsService settingsService,
                                  DirigeraHttpClient httpClient,
                                  DirigeraDeviceParser parser,
                                  DirigeraPollingService pollingService,
                                  AuditService auditService,
                                  AuditActorResolver actorResolver,
                                  Clock clock) {
        this(settingsService, httpClient, parser, pollingService, auditService, actorResolver, clock,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dirigera-pairing");
                    t.setDaemon(true);
                    return t;
                }));
    }

    DirigeraPairingService(DirigeraSettingsService settingsService,
                           DirigeraHttpClient httpClient,
                           DirigeraDeviceParser parser,
                           DirigeraPollingService pollingService,
                           AuditService auditService,
                           AuditActorResolver actorResolver,
                           Clock clock,
                           ScheduledExecutorService executor) {
        this.settingsService = settingsService;
        this.httpClient = httpClient;
        this.parser = parser;
        this.pollingService = pollingService;
        this.auditService = auditService;
        this.actorResolver = actorResolver;
        this.clock = clock;
        this.executor = executor;
    }

    public DirigeraPairingStatus start() {
        String host = settingsService.get().host();
        if (host == null) {
            throw new IllegalStateException("Erst die Hub-Adresse eintragen");
        }
        synchronized (this) {
            // "starting" deckt das Fenster der Netzwerkaufrufe ab, ohne die Sperre darueber zu halten
            if (starting || status.state() == State.PRESS_BUTTON) {
                throw new PairingInProgressException("Kopplung laeuft bereits");
            }
            starting = true;
        }
        try {
            String certSha256 = httpClient.fetchFingerprint(host);
            Pkce pkce = Pkce.generate();
            String code = httpClient.authorize(host, certSha256, pkce.challenge());
            Instant expiresAt = Instant.now(clock).plus(BUTTON_WINDOW);
            // Der Task laeuft ohne Request-Kontext; das Audit soll trotzdem den Admin nennen
            Attempt attempt = new Attempt(host, certSha256, code, pkce.verifier(), expiresAt,
                    actorResolver.currentActor());
            synchronized (this) {
                current = attempt;
                status = new DirigeraPairingStatus(State.PRESS_BUTTON, expiresAt, null);
                attempt.future = executor.scheduleWithFixedDelay(
                        () -> tick(attempt), POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
                return status;
            }
        } finally {
            synchronized (this) {
                starting = false;
            }
        }
    }

    public synchronized DirigeraPairingStatus status() {
        return status;
    }

    public void unpair() {
        String host = settingsService.get().host();
        settingsService.clearPairing();
        synchronized (this) {
            // Ein laufender Vorgang bleibt, nur ein alter PAIRED/FAILED-Stand waere jetzt irrefuehrend
            if (status.state() != State.PRESS_BUTTON) {
                status = DirigeraPairingStatus.idle();
            }
        }
        pollingService.onUnpaired();
        auditService.record("dirigera.unpair", host);
    }

    public void updateHost(String host) {
        String trimmed = host == null ? "" : host.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_HOST_LENGTH
                || trimmed.contains("/") || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "Hub-Adresse ungueltig: IP oder Hostname ohne Schraegstrich und Leerzeichen");
        }
        settingsService.saveHost(trimmed);
        auditService.record("dirigera.hub.update", trimmed);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Ein Takt des Wartens; wirft nie, sonst stuende der Task still und der Status ewig auf PRESS_BUTTON. */
    private void tick(Attempt attempt) {
        try {
            if (!isCurrent(attempt)) {
                cancel(attempt);
                return;
            }
            Optional<String> token = requestToken(attempt);
            if (token.isPresent()) {
                complete(attempt, token.get());
            } else if (!Instant.now(clock).isBefore(attempt.expiresAt)) {
                finish(attempt, State.FAILED, "Kein Knopfdruck innerhalb von 90 s (letzte Antwort: "
                        + lastResponse(attempt) + ")");
            }
        } catch (Exception e) {
            log.warn("Dirigera-Kopplung abgebrochen: {}", e.getMessage());
            finish(attempt, State.FAILED, "Kopplung fehlgeschlagen: " + describe(e));
        }
    }

    /** Ein Verbindungsfehler zaehlt als "wartet" (der Hub kann beim Knopfdruck kurz haengen). */
    private Optional<String> requestToken(Attempt attempt) {
        try {
            Optional<String> token = httpClient.requestToken(
                    attempt.host, attempt.certSha256, attempt.code, attempt.verifier);
            attempt.sawResponse = true;
            return token;
        } catch (DirigeraException e) {
            log.debug("Dirigera-Token-Abfrage ohne Antwort: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void complete(Attempt attempt, String token) {
        DirigeraDevices devices = parser.parse(httpClient.getDevices(attempt.host, attempt.certSha256, token));
        if (devices.gatewayId() == null) {
            finish(attempt, State.FAILED, "Hub-Antwort ohne Gateway-Id");
            return;
        }
        synchronized (this) {
            if (!isCurrent(attempt)) {
                return;
            }
            // Neukoppeln uebernimmt die neue Identitaet: der Knopfdruck ist der Nachweis
            settingsService.savePairing(token, attempt.certSha256, devices.gatewayId());
            finish(attempt, State.PAIRED, null);
        }
        auditService.record(attempt.actor, "dirigera.pair", attempt.host);
    }

    /**
     * Die letzte Antwort des Clients nur zeigen, wenn sie aus diesem Versuch stammt: sonst koennte
     * sie die Erfolgsantwort einer frueheren Kopplung samt Token sein.
     */
    private String lastResponse(Attempt attempt) {
        if (!attempt.sawResponse) {
            return "keine";
        }
        return httpClient.lastTokenResponse()
                .map(r -> r.status() + " " + safeBody(r.body()))
                .orElse("keine");
    }

    private static String safeBody(String body) {
        if (body == null) {
            return "";
        }
        if (body.contains("access_token")) {
            return "(ausgeblendet)";
        }
        return body.length() > MAX_BODY_IN_ERROR ? body.substring(0, MAX_BODY_IN_ERROR) : body;
    }

    private synchronized void finish(Attempt attempt, State state, String error) {
        if (!isCurrent(attempt)) {
            return;
        }
        status = new DirigeraPairingStatus(state, null, error);
        current = null;
        cancel(attempt);
    }

    private synchronized boolean isCurrent(Attempt attempt) {
        return current == attempt && status.state() == State.PRESS_BUTTON;
    }

    private static void cancel(Attempt attempt) {
        ScheduledFuture<?> future = attempt.future;
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static final class Attempt {
        private final String host;
        private final String certSha256;
        private final String code;
        private final String verifier;
        private final Instant expiresAt;
        private final AuditActor actor;
        private volatile ScheduledFuture<?> future;
        private volatile boolean sawResponse;

        private Attempt(String host, String certSha256, String code, String verifier,
                        Instant expiresAt, AuditActor actor) {
            this.host = host;
            this.certSha256 = certSha256;
            this.code = code;
            this.verifier = verifier;
            this.expiresAt = expiresAt;
            this.actor = actor;
        }
    }
}
