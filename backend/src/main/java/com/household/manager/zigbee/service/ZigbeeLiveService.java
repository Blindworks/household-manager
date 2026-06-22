package com.household.manager.zigbee.service;

import com.household.manager.zigbee.dto.ZigbeeLiveResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verteilt eingetroffene Zigbee-Messwerte per SSE an verbundene Clients.
 * Push-getrieben aus dem MQTT-Empfang; kein eigener Scheduler.
 */
@Service
@Slf4j
public class ZigbeeLiveService {

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(ZigbeeLiveResponse event) {
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("live").data(event));
            } catch (Exception ex) {
                emitters.remove(emitter);
            }
        });
    }
}
