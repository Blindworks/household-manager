package com.household.manager.communicator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sorgt dafuer, dass der lokale Whisper-Server (speaches) sein Modell hat. Das Image bringt keines mit
 * und laedt auch keines von selbst — ohne diesen Schritt antwortete jede Aufnahme mit 404.
 *
 * <p>Der Download laeuft immer auf einem eigenen Daemon-Thread (er dauert Minuten und darf weder den
 * Start noch eine Anfrage aufhalten) und nie doppelt. Beim Start wird mehrfach versucht, weil der
 * Whisper-Container nach einem Deploy spaeter bereit sein kann als das Backend. Wirft nie.
 */
@Component
@Slf4j
public class SpeechModelInstaller {

    static final int STARTUP_ATTEMPTS = 10;
    static final long RETRY_DELAY_MS = 30_000;

    private final CommunicatorProperties properties;
    private final SpeechToTextClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastError;

    public SpeechModelInstaller(CommunicatorProperties properties, SpeechToTextClient client) {
        this.properties = properties;
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        start(STARTUP_ATTEMPTS);
    }

    /** Nach einem 404 des Dienstes: Modell nachladen, falls nicht ohnehin schon ein Download laeuft. */
    public void requestInstall() {
        start(1);
    }

    private void start(int attempts) {
        if (!isActive() || !running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> install(attempts), "communicator-model-install");
        thread.setDaemon(true);
        thread.start();
    }

    /** Frische Pruefung des Dienstes fuer die Admin-Seite. Wirft nie. */
    CommunicatorLogDtos.SpeechHealth health() {
        CommunicatorProperties.Stt stt = properties.getStt();
        boolean reachable = false;
        Boolean installed = null;
        Integer responseMs = null;
        String error = null;
        if (stt.isConfigured()) {
            long start = System.nanoTime();
            try {
                installed = client.isModelInstalled();
                reachable = true;
                responseMs = (int) ((System.nanoTime() - start) / 1_000_000);
            } catch (RuntimeException ex) {
                error = ex.getMessage();
            }
        }
        return new CommunicatorLogDtos.SpeechHealth(stt.isConfigured(), stt.getModel(), stt.getLanguage(),
                reachable, installed, responseMs, error, running.get(), lastError);
    }

    boolean isActive() {
        return properties.isEnabled() && properties.getStt().isConfigured()
                && properties.getStt().isAutoInstallModel();
    }

    /** Sichtbar fuer Tests; laeuft sonst nur auf dem eigenen Thread. */
    void install(int attempts) {
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    client.installModel();
                    lastError = null;
                    log.info("Sprachmodell {} ist bereit", properties.getStt().getModel());
                    return;
                } catch (RuntimeException ex) {
                    lastError = ex.getMessage();
                    log.warn("Sprachmodell {} nicht geladen (Versuch {}/{}): {}",
                            properties.getStt().getModel(), attempt, attempts, ex.getMessage());
                }
                if (attempt < attempts && !pause()) {
                    return;
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** @return false, wenn der Thread unterbrochen wurde (Herunterfahren) */
    boolean pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
