package com.household.manager.entitystate.mapper;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bildet die Kameraliste des blink-vision-Sidecars auf Entitäten ab:
 * eine {@code binary_sensor.blink_<cameraId>_armed} je Kamera und eine
 * {@code binary_sensor.blink_sync_<slug>_armed} je Sync-Modul.
 * Die cameraId ist die stabile Blink-Hardware-Id (Namen sind umbenennbar);
 * Sync-Module haben keine solche Id, ihr Name wird deshalb ge-sluggt —
 * ein umbenanntes Sync-Modul ergibt eine NEUE Entität (dokumentierter Preis).
 */
@Component
public class BlinkEntityMapper {

    /** Prefix, das die Sync-Referenz von der Kamera-Referenz unterscheidet (siehe Klassendoku). */
    private static final String SYNC_REF_PREFIX = "sync_";

    public List<EntityStateUpdate> map(List<SidecarCamera> cameras) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        // LinkedHashMap: erste Kamera je Sync-Modul gewinnt, Reihenfolge bleibt stabil (nur fuer
        // Determinismus der Ausgabe relevant, keine fachliche Bedeutung).
        Map<String, SidecarCamera> syncRepresentatives = new LinkedHashMap<>();
        for (SidecarCamera camera : cameras) {
            updates.add(cameraUpdate(camera));
            syncRepresentatives.putIfAbsent(camera.syncName(), camera);
        }
        for (SidecarCamera representative : syncRepresentatives.values()) {
            updates.add(syncUpdate(representative));
        }
        return updates;
    }

    private EntityStateUpdate cameraUpdate(SidecarCamera camera) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", camera.name());
        attributes.put("type", camera.type());
        if (camera.battery() != null) {
            attributes.put("battery", camera.battery());
        }
        attributes.put("syncName", camera.syncName());
        // Bewusst KEIN "deviceClass": anders als bei Nuki/Tractive gibt es fuer "scharf/unscharf"
        // keinen passenden Home-Assistant-Standardwert ("safety" trifft es nicht), und ein
        // erfundener Wert waere schlechter als keiner. Vor allem aber ist deviceClass hier eine
        // Falle: "door" wuerde diese Kamera-Entitaeten in den Fenster-/Tuerkontakt-Check von
        // mode-activation-check.ts (Zeile ~36, Filter auf === 'door') hineinziehen und bei jeder
        // Aktivierung von "Abwesend" eine unsinnige Warnung ausloesen. Wer hier "Konsistenz zu
        // den anderen Mappern herstellen" will, ist genau einen Schritt von diesem schwer
        // auffindbaren Fehlalarm entfernt.
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.BLINK, camera.cameraId(), "armed"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.BLINK)
                .sourceRef(camera.cameraId())
                .friendlyName(camera.name() + " scharf")
                .state(camera.armed() ? "on" : "off")
                .attributes(attributes)
                .build();
    }

    private EntityStateUpdate syncUpdate(SidecarCamera camera) {
        // Sync-Module haben keine stabile Hardware-Id in der Sidecar-Antwort, nur einen
        // (umbenennbaren) Namen. EntityIds.build sluggt ihn (Locale.ROOT, Umlaute
        // transliteriert) genau wie an jeder anderen Stelle im Projekt (siehe
        // CalendarCategoryKeyGenerator) — keine eigene Slug-Implementierung noetig.
        // Zwei Sync-Module, deren Namen ausschliesslich aus Sonderzeichen bestehen, wuerden auf
        // derselben Entity-Id kollidieren (der Slug-Rest waere in beiden Faellen leer); das ist
        // bei von Menschen vergebenen Blink-Modulnamen praktisch ausgeschlossen und deshalb
        // bewusst nicht abgefangen.
        String syncRef = SYNC_REF_PREFIX + camera.syncName();
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.BLINK, syncRef, "armed"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.BLINK)
                .sourceRef(syncRef)
                .friendlyName("Blink " + camera.syncName() + " scharf")
                .state(camera.syncArmed() ? "on" : "off")
                .attributes(Map.of("syncName", camera.syncName()))
                .build();
    }
}
