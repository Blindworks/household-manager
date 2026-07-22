package com.household.manager.vision;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.VisionRecognition;
import com.household.manager.repository.VisionRecognitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verarbeitet Erkennungs-Webhooks des blink-vision-Sidecars: persistiert die Historie
 * und feuert pro Meldung ein Entity-Event (event.vision_blink_door_person).
 * Hook-Muster wie {@link com.household.manager.entitystate.EntityStateService}: Persistenz-
 * und Event-Fehler reissen einander nicht mit. Ausbleibender Heartbeat setzt die Entitaet
 * auf "unavailable" (Muster aus {@link com.household.manager.tablet.TabletPresenceService}).
 */
@Service
@Slf4j
public class VisionRecognitionService {

    static final String SOURCE_REF = "blink door";
    static final String STATE_UNKNOWN = "unknown";
    static final String STATE_UNAVAILABLE = "unavailable";

    /** Erkennungs-Meldung des Sidecars. */
    public record RecognitionReport(List<RecognizedPerson> persons, int unknownFaces, byte[] thumbnail) {}

    /** Eine erkannte Person mit Konfidenz (0..1). */
    public record RecognizedPerson(Long personId, String name, BigDecimal confidence) {}

    private final VisionRecognitionRepository repository;
    private final EntityStateService entityStateService;
    private final VisionProperties properties;
    private final Clock clock;
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>();
    private final AtomicReference<Boolean> reportedUnavailable = new AtomicReference<>(false);

    public VisionRecognitionService(VisionRecognitionRepository repository,
                                    EntityStateService entityStateService,
                                    VisionProperties properties,
                                    Clock clock) {
        this.repository = repository;
        this.entityStateService = entityStateService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Bewusst NICHT {@code @Transactional}: Persistenz ({@link #persistSafely}) und
     * Event ({@link #fireEventSafely}) sind unabhaengige Hooks, die einander nicht
     * mitreissen duerfen — wie bei {@code TabletPresenceService.reportPresence} und
     * {@code EntityStateService.reportState} selbst gibt es hier keine gemeinsame
     * Atomaritaet zu wahren. {@code repository.save(...)} traegt seine eigene
     * Transaktionsgrenze (Spring-Data-Default), {@code reportEvent} ebenso
     * (REQUIRES_NEW in {@code EntityStateWriter}).
     */
    public void processRecognition(RecognitionReport report) {
        heartbeat();
        RecognizedPerson best = bestPerson(report);
        persistSafely(report, best);
        fireEventSafely(report, best);
    }

    public void heartbeat() {
        heartbeatAt(clock.instant());
    }

    void heartbeatAt(Instant instant) {
        lastHeartbeat.set(instant);
        reportedUnavailable.set(false);
    }

    @Scheduled(fixedDelayString = "${vision.heartbeat-check-ms:60000}")
    public void checkHeartbeat() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant last = lastHeartbeat.get();
        if (last == null || reportedUnavailable.get()) {
            return;
        }
        Duration timeout = Duration.ofSeconds(properties.getHeartbeatTimeoutSeconds());
        if (last.isBefore(clock.instant().minus(timeout))) {
            log.warn("blink-vision-Sidecar sendet keinen Heartbeat mehr, Entitaet geht auf unavailable");
            reportedUnavailable.set(true);
            entityStateService.reportState(baseUpdate(STATE_UNAVAILABLE, Map.of()));
        }
    }

    @Transactional(readOnly = true)
    public List<VisionRecognition> getRecent(int limit) {
        return repository.findAllByOrderByRecognizedAtDesc(PageRequest.of(0, limit));
    }

    private void persistSafely(RecognitionReport report, RecognizedPerson best) {
        try {
            repository.save(VisionRecognition.builder()
                    .recognizedAt(LocalDateTime.now(clock))
                    .personId(best == null ? null : best.personId())
                    .personName(best == null ? null : best.name())
                    .confidence(best == null ? null : best.confidence())
                    .unknownFaces(report.unknownFaces())
                    .thumbnail(report.thumbnail())
                    .build());
        } catch (Exception ex) {
            log.warn("Erkennung konnte nicht persistiert werden: {}", ex.getMessage());
        }
    }

    private void fireEventSafely(RecognitionReport report, RecognizedPerson best) {
        try {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("unknownFaces", report.unknownFaces());
            String state = STATE_UNKNOWN;
            if (best != null) {
                state = best.name();
                attributes.put("personId", best.personId());
                attributes.put("confidence", best.confidence());
            }
            entityStateService.reportEvent(baseUpdate(state, attributes));
        } catch (Exception ex) {
            log.warn("Erkennungs-Event konnte nicht gefeuert werden: {}", ex.getMessage());
        }
    }

    private RecognizedPerson bestPerson(RecognitionReport report) {
        return report.persons().stream()
                .max(Comparator.comparing(RecognizedPerson::confidence))
                .orElse(null);
    }

    private EntityStateUpdate baseUpdate(String state, Map<String, Object> attributes) {
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.VISION, SOURCE_REF, "person"))
                .domain(EntityDomain.EVENT)
                .source(EntitySource.VISION)
                .sourceRef(SOURCE_REF)
                .friendlyName("Haustuer Gesichtserkennung")
                .state(state)
                .attributes(attributes)
                .build();
    }
}
