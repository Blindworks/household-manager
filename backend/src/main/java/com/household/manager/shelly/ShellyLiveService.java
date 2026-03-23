package com.household.manager.shelly;

import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
@Slf4j
public class ShellyLiveService {

    private final ShellyService shellyService;
    private final TaskScheduler taskScheduler;
    private final long intervalMs;

    public ShellyLiveService(ShellyService shellyService,
                              TaskScheduler taskScheduler,
                              @Value("${shelly.polling.interval-ms:5000}") long intervalMs) {
        this.shellyService = shellyService;
        this.taskScheduler = taskScheduler;
        this.intervalMs = intervalMs;
    }

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));

        ensureSchedulerRunning();
        return emitter;
    }

    private void ensureSchedulerRunning() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                return;
            }
            long delay = Math.max(500, intervalMs);
            scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                    this::pollAndBroadcast,
                    Duration.ofMillis(delay)
            );
            log.info("Shelly live polling started ({} ms)", delay);
        }
    }

    private void stopScheduler() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
                log.info("Shelly live polling stopped (no clients)");
            }
        }
    }

    private void pollAndBroadcast() {
        if (emitters.isEmpty()) {
            stopScheduler();
            return;
        }

        try {
            List<ShellyStatusDto> statuses = shellyService.getAllDevicesStatus();
            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("live").data(statuses));
                } catch (Exception ex) {
                    removeEmitter(emitter);
                }
            });
        } catch (Exception ex) {
            log.debug("Shelly live polling failed: {}", ex.getMessage());
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            stopScheduler();
        }
    }
}
