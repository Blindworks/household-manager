package com.household.manager.dirigera;

import com.household.manager.dirigera.client.DirigeraCertificateMismatchException;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.systemlog.SystemJobMonitor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Haelt den Ereignisstrom (WebSocket) des Dirigera-Hubs offen. Tastendruecke gibt es nur hier
 * (der Poll sieht sie nie), Fenster-/Tuerkontakte kommen hier sofort statt mit bis zu 60 s
 * Verzoegerung; der Minuten-Poll bleibt der Abgleich fuer verlorene Ereignisse.
 *
 * <p>{@link #supervise()} laeuft alle {@code dirigera.events.check-interval-ms}: verbindet, wenn
 * gekoppelt und keine Verbindung steht, baut neu auf, wenn sich Host/Zertifikat/Token geaendert
 * haben (Neukoppeln, Entkoppeln), und pingt sonst. Kommt drei Intervalle lang nichts an (auch kein
 * Pong), gilt die Verbindung als tot — ein weggezogener Hub schliesst den Socket nicht. Zwischen
 * Abbruch und Neuaufbau gedrueckte Tasten gehen verloren; der Hub spielt nichts nach.
 */
@Service
@Slf4j
public class DirigeraEventStreamService {

    private static final long CONNECT_WAIT_SECONDS = 10;
    private static final int SILENT_INTERVALS = 3;

    private final DirigeraSettingsService settingsService;
    private final DirigeraHttpClient httpClient;
    private final DirigeraEventParser parser;
    private final DirigeraEntityMapper mapper;
    private final DirigeraButtonRegistry buttons;
    private final EntityStateService entityStateService;
    private final DirigeraPollingService pollingService;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;
    private final boolean enabled;
    private final long checkIntervalMs;

    private volatile Connection current;

    public DirigeraEventStreamService(DirigeraSettingsService settingsService,
                                      DirigeraHttpClient httpClient,
                                      DirigeraEventParser parser,
                                      DirigeraEntityMapper mapper,
                                      DirigeraButtonRegistry buttons,
                                      EntityStateService entityStateService,
                                      DirigeraPollingService pollingService,
                                      SystemJobMonitor jobMonitor,
                                      Clock clock,
                                      @Value("${dirigera.enabled:true}") boolean enabled,
                                      @Value("${dirigera.events.check-interval-ms:20000}") long checkIntervalMs) {
        this.settingsService = settingsService;
        this.httpClient = httpClient;
        this.parser = parser;
        this.mapper = mapper;
        this.buttons = buttons;
        this.entityStateService = entityStateService;
        this.pollingService = pollingService;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
        this.enabled = enabled;
        this.checkIntervalMs = checkIntervalMs;
    }

    public boolean isConnected() {
        Connection connection = current;
        return connection != null && connection.isOpen();
    }

    @Scheduled(fixedDelayString = "${dirigera.events.check-interval-ms:20000}", initialDelayString = "15000")
    public synchronized void supervise() {
        try {
            DirigeraHubSettings settings = settingsService.get();
            if (!enabled || !settings.isPaired()) {
                close("nicht gekoppelt");
                jobMonitor.reportInactive("nicht gekoppelt");
                return;
            }
            Connection connection = current;
            if (connection != null && connection.isOpen() && connection.matches(settings)) {
                if (connection.isSilentSince(Instant.now(clock).minusMillis(SILENT_INTERVALS * checkIntervalMs))) {
                    log.warn("Dirigera-Ereignisstrom seit {} s still, baue neu auf", SILENT_INTERVALS * checkIntervalMs / 1000);
                    close("still");
                } else {
                    connection.ping();
                    return;
                }
            }
            close("Neuaufbau");
            connect(settings);
        } catch (Exception e) {
            jobMonitor.reportFailure(describe(e));
            log.warn("Dirigera-Ereignisstrom: {}", describe(e));
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        close("Herunterfahren");
    }

    private void connect(DirigeraHubSettings settings) {
        Connection connection = new Connection(settings);
        WebSocket socket;
        try {
            socket = httpClient.openEventStream(settings.host(), settings.certSha256(), settings.accessToken(), connection)
                    .get(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (DirigeraCertificateMismatchException e) {
            fail("Zertifikat geändert – neu koppeln");
            return;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            fail(hasCause(cause, java.security.cert.CertificateException.class)
                    ? "Zertifikat geändert – neu koppeln"
                    : "Ereignisstrom nicht erreichbar: " + describe(cause));
            return;
        } catch (TimeoutException e) {
            fail("Ereignisstrom: keine Antwort vom Hub");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Ereignisstrom: unterbrochen");
            return;
        }
        connection.attach(socket);
        current = connection;
        log.info("Dirigera-Ereignisstrom verbunden");
    }

    private void fail(String reason) {
        jobMonitor.reportFailure(reason);
        log.warn("Dirigera-Ereignisstrom: {}", reason);
    }

    private void close(String reason) {
        Connection connection = current;
        current = null;
        if (connection != null) {
            log.debug("Dirigera-Ereignisstrom geschlossen: {}", reason);
            connection.abort();
        }
    }

    /** Eine vollstaendige Nachricht des Hubs; wirft nie, damit der Strom weiterliest. */
    void handleMessage(String json) {
        try {
            parser.parseRemotePress(json).ifPresent(press -> entityStateService.reportEvent(
                    mapper.buttonEvent(press, buttons.find(press.deviceId()).orElse(null))));
            parser.parseContactChange(json).ifPresent(pollingService::applyContactChange);
        } catch (Exception e) {
            log.warn("Dirigera-Ereignis nicht verarbeitet: {}", describe(e));
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** Eine Verbindung samt den Zugangsdaten, mit denen sie aufgebaut wurde; Nachrichten alter Verbindungen werden verworfen. */
    private final class Connection implements WebSocket.Listener {

        private final DirigeraHubSettings settings;
        private final StringBuilder buffer = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean closed;
        private volatile Instant lastActivity = Instant.now(clock);

        Connection(DirigeraHubSettings settings) {
            this.settings = settings;
        }

        void attach(WebSocket webSocket) {
            this.socket = webSocket;
            this.lastActivity = Instant.now(clock);
        }

        boolean matches(DirigeraHubSettings other) {
            return Objects.equals(settings.host(), other.host())
                    && Objects.equals(settings.certSha256(), other.certSha256())
                    && Objects.equals(settings.accessToken(), other.accessToken());
        }

        boolean isOpen() {
            return !closed;
        }

        boolean isSilentSince(Instant threshold) {
            return lastActivity.isBefore(threshold);
        }

        void ping() {
            WebSocket ws = socket;
            if (ws != null) {
                ws.sendPing(ByteBuffer.allocate(0));
            }
        }

        void abort() {
            closed = true;
            WebSocket ws = socket;
            if (ws != null) {
                ws.abort();
            }
        }

        private boolean isCurrent() {
            return current == this && !closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastActivity = Instant.now(clock);
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                if (isCurrent()) {
                    handleMessage(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastActivity = Instant.now(clock);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastActivity = Instant.now(clock);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            log.info("Dirigera-Ereignisstrom vom Hub geschlossen ({} {})", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed = true;
            log.warn("Dirigera-Ereignisstrom abgebrochen: {}", describe(error));
        }
    }
}
