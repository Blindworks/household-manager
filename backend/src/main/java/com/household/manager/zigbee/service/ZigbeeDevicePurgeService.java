package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.ZigbeeDeviceKnownToBridgeException;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * "Aus Household Manager entfernen": loescht Messwerte, Geraetezeile, Entitaeten und
 * deren Kachelregeln in EINER Transaktion. Nur fuer Geraete, die zigbee2mqtt nicht
 * (mehr) kennt — sonst legte die naechste Nachricht alles wieder an und der Knopf
 * waere eine Luege.
 * <p>
 * Die Kachelregeln muessen explizit mit: entity_tile_visibility hat keinen
 * Fremdschluessel auf entity_states, eine verwaiste NEVER-Zeile griffe beim naechsten
 * gleichnamigen Geraet wieder (die Falle aus dem Helfer-Kapitel in CLAUDE.md).
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDevicePurgeService {

    public record PurgeResult(String friendlyName, int measurements, int entities) {
    }

    private final ZigbeeDeviceRegistry registry;
    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final EntityStateRepository entityStateRepository;
    private final EntityTileVisibilityRepository tileVisibilityRepository;
    private final AuditService auditService;

    @Transactional
    public PurgeResult purge(Long deviceId) {
        ZigbeeDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Zigbee-Geraet", "id", deviceId));
        String friendlyName = device.getFriendlyName();
        if (registry.findByFriendlyName(friendlyName).isPresent()) {
            throw new ZigbeeDeviceKnownToBridgeException(
                    "'" + friendlyName + "' ist zigbee2mqtt noch bekannt — zuerst dort entfernen.");
        }

        List<EntityState> entities = entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE).stream()
                .filter(entity -> friendlyName.equals(entity.getSourceRef()))
                .toList();
        List<String> entityIds = entities.stream().map(EntityState::getEntityId).toList();
        if (!entityIds.isEmpty()) {
            List<EntityTileVisibility> rules = tileVisibilityRepository.findByEntityIdIn(entityIds);
            tileVisibilityRepository.deleteAll(rules);
            entityStateRepository.deleteAll(entities);
        }
        int measurements = measurementRepository.deleteByDeviceId(deviceId);
        deviceRepository.delete(device);

        auditService.record("zigbee.device.purge",
                friendlyName + ": " + measurements + " Messwerte, " + entities.size() + " Entitaeten");
        return new PurgeResult(friendlyName, measurements, entities.size());
    }
}
