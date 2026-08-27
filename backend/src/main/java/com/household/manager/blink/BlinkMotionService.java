package com.household.manager.blink;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nimmt Bewegungsmeldungen des blink-vision-Sidecars entgegen: je Bewegung ein
 * Ereignis {@code event.blink_<cameraId>_motion} (Flows: „Bewegung + Abwesend
 * → Push") und die letzte Bewegung je Kamera fuer die Dashboard-Anzeige.
 *
 * Die letzte Bewegung lebt NUR im Speicher (Muster NetworkDeviceStatusMonitor):
 * ueberlebt keinen Neustart — nach einem Deploy ist die Anzeige leer, bis die
 * naechste Bewegung kommt. Bewusste Grenze, keine Tabelle wert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkMotionService {

    private final EntityStateService entityStateService;

    /**
     * Wird vom HTTP-Webhook-Pfad UND (potenziell) vom Poller-Pfad gleichzeitig
     * beschrieben — ConcurrentHashMap statt HashMap, damit ein gleichzeitiges
     * put/get nie eine inkonsistente interne Struktur sieht.
     */
    private final Map<String, LastMotion> lastMotions = new ConcurrentHashMap<>();

    /** Eine Bewegung laut Sidecar-Webhook (createdAt als ISO-String durchgereicht). */
    public record MotionReport(String cameraId, String cameraName, String clipId, String createdAt) {}

    /** Letzte bekannte Bewegung einer Kamera (fuer die Anreicherung von GET /cameras). */
    public record LastMotion(String createdAt, String clipId) {}

    public void processMotions(List<MotionReport> motions) {
        for (MotionReport motion : motions) {
            fireEventSafely(motion);
            lastMotions.put(motion.cameraId(),
                    new LastMotion(motion.createdAt(), motion.clipId()));
        }
    }

    public Optional<LastMotion> lastMotion(String cameraId) {
        return Optional.ofNullable(lastMotions.get(cameraId));
    }

    /** Muster VisionRecognitionService.fireEventSafely: ein Event-Fehler darf
     *  weder die uebrigen Meldungen noch das Merken mitreissen. */
    private void fireEventSafely(MotionReport motion) {
        try {
            entityStateService.reportEvent(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.BLINK,
                            motion.cameraId(), "motion"))
                    .domain(EntityDomain.EVENT)
                    .source(EntitySource.BLINK)
                    .sourceRef(motion.cameraId())
                    .friendlyName(motion.cameraName() + " Bewegung")
                    .state("motion")
                    .attributes(Map.of(
                            "cameraName", motion.cameraName(),
                            "clipId", motion.clipId(),
                            "createdAt", motion.createdAt()))
                    .build());
        } catch (Exception ex) {
            log.warn("Bewegungs-Event fuer Kamera {} nicht gefeuert: {}",
                    motion.cameraId(), ex.getMessage());
        }
    }
}
