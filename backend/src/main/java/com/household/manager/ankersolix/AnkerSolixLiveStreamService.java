package com.household.manager.ankersolix;

import com.household.manager.ankersolix.dto.AnkerSolixLiveDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Streams live Anker Solix power-flow data to SSE subscribers.
 * The polling loop starts with the first subscriber and stops when the last one disconnects.
 */
@Service
@ConditionalOnProperty(name = "ankersolix.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class AnkerSolixLiveStreamService {

    private final AnkerSolixService ankerSolixService;
    private final TaskScheduler taskScheduler;

    @Value("${ankersolix.live.interval-ms:10000}")
    private long intervalMs;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;

    /**
     * Creates a new SSE emitter and registers it for live updates.
     * Starts the background polling loop if it is not already running.
     *
     * @return a configured {@link SseEmitter} with no timeout
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));

        ensureSchedulerRunning();
        return emitter;
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            stopScheduler();
        }
    }

    private void ensureSchedulerRunning() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                return;
            }
            long delay = Math.max(1000, intervalMs);
            scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::pollAndBroadcast, Duration.ofMillis(delay));
            log.info("Anker Solix live polling started ({} ms)", delay);
        }
    }

    private void stopScheduler() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
                log.info("Anker Solix live polling stopped");
            }
        }
    }

    private void pollAndBroadcast() {
        if (emitters.isEmpty()) {
            stopScheduler();
            return;
        }

        try {
            AnkerSolixLiveDto liveData = ankerSolixService.getLiveData();

            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("live").data(liveData));
                } catch (Exception ex) {
                    removeEmitter(emitter);
                }
            });
        } catch (Exception ex) {
            log.debug("Anker Solix live polling failed: {}", ex.getMessage());
        }
    }
}
