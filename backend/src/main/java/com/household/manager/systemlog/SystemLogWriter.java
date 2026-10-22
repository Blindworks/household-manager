package com.household.manager.systemlog;

import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.repository.SystemLogEntryRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Schreibt Protokollzeilen asynchron: Aufrufer legen nur in eine Queue ab ({@code offer}, nie blockierend,
 * bei voller Queue wird verworfen und mitgezaehlt), ein eigener Daemon-Thread speichert. Wirft nie.
 * Der Logger hat ein eigenes Topic, damit er vom Log-Appender (Task 8) ausgenommen werden kann - sonst
 * erzeugte ein Speicherfehler eine neue Protokollzeile, die wieder scheitert.
 */
@Component
@Slf4j(topic = SystemLogWriter.INTERNAL_LOGGER)
public class SystemLogWriter {

    public static final String INTERNAL_LOGGER = "com.household.manager.systemlog.writer";

    static final int DEFAULT_CAPACITY = 5000;
    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_SHORT = 200;
    private static final String WRITER_THREAD = "system-log-writer";

    private record Item(SystemLogEvent event, Duration window) { }

    private final SystemLogEntryRepository repository;
    private final Clock clock;
    private final BlockingQueue<Item> queue;
    private final Thread thread;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong unreportedDropped = new AtomicLong();
    private volatile boolean running = true;

    @Autowired
    public SystemLogWriter(Clock clock, SystemLogEntryRepository repository) {
        this(clock, repository, DEFAULT_CAPACITY);
    }

    SystemLogWriter(Clock clock, SystemLogEntryRepository repository, int capacity) {
        this.clock = clock;
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.thread = new Thread(this::loop, WRITER_THREAD);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void write(SystemLogEvent event) {
        enqueue(new Item(event, null));
    }

    /** Erhoeht {@code repeat_count} der juengsten gleichen Zeile im Fenster, sonst neue Zeile. */
    public void writeOrIncrement(SystemLogEvent event, Duration window) {
        enqueue(new Item(event, window));
    }

    public boolean isWriterThread() {
        return Thread.currentThread() == thread;
    }

    /** Gesamtzahl der wegen voller Queue verworfenen Ereignisse. */
    public long droppedCount() {
        return dropped.get();
    }

    private void enqueue(Item item) {
        try {
            if (item.event() == null) {
                return;
            }
            inFlight.incrementAndGet();
            if (!queue.offer(item)) {
                inFlight.decrementAndGet();
                dropped.incrementAndGet();
                unreportedDropped.incrementAndGet();
            }
        } catch (Exception ex) {
            log.debug("Systemprotokoll: Ereignis nicht eingereiht", ex);
        }
    }

    private void loop() {
        while (running) {
            Item item;
            try {
                item = queue.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                persist(item);
            } catch (Exception ex) {
                log.debug("Systemprotokoll: Speichern fehlgeschlagen", ex);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private void persist(Item item) {
        SystemLogEvent event = item.event();
        LocalDateTime at = event.occurredAt() != null ? event.occurredAt() : LocalDateTime.now(clock);
        String message = withDroppedSuffix(truncate(event.message(), MAX_MESSAGE));
        String source = truncate(event.source(), MAX_SHORT);
        if (item.window() != null) {
            var existing = repository.findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(
                    event.kind(), source, message, at.minus(item.window()));
            if (existing.isPresent()) {
                SystemLogEntry entry = existing.get();
                entry.setRepeatCount(entry.getRepeatCount() + 1);
                repository.save(entry);
                return;
            }
        }
        SystemLogEntry entry = new SystemLogEntry();
        entry.setOccurredAt(at);
        entry.setKind(event.kind());
        entry.setStatus(event.status());
        entry.setSource(source);
        entry.setTitle(truncate(event.title(), MAX_SHORT));
        entry.setMessage(message);
        entry.setDetails(event.detailsJson());
        entry.setFlowId(event.flowId());
        entry.setRunId(event.runId());
        repository.save(entry);
    }

    /** Haengt einmalig an, wie viele Eintraege zwischenzeitlich verworfen wurden. */
    private String withDroppedSuffix(String message) {
        long n = unreportedDropped.getAndSet(0);
        if (n == 0) {
            return message;
        }
        String suffix = " (" + n + " Einträge verworfen)";
        String base = message == null ? "" : message;
        if (base.length() + suffix.length() > MAX_MESSAGE) {
            base = base.substring(0, MAX_MESSAGE - suffix.length());
        }
        return base + suffix;
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    /** Wartet, bis die Queue abgearbeitet ist (nur fuer Tests). */
    void flushForTest() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        thread.interrupt();
    }
}
