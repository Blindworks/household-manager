package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.zigbee.dto.FlowReferenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Welche Flows verwenden Entitaeten dieses Geraets? Fuer die Warnung vor Umbenennen
 * und Entfernen — warnt, blockiert nicht.
 * <p>
 * Bewusst eine String-Suche im Flow-JSON: Node-Configs sind eine freie Map, eine
 * strukturierte Suche hinkte bei jedem neuen Node-Typ nach. Ein falsch-positiver
 * Teilstring-Treffer ist bei einer Warnung akzeptabel, ein falsch-negativer nicht.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeFlowReferenceService {

    private final EntityStateRepository entityStateRepository;
    private final FlowRepository flowRepository;

    @Transactional(readOnly = true)
    public List<FlowReferenceResponse> references(String friendlyName) {
        List<String> entityIds = entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE).stream()
                .filter(entity -> friendlyName.equals(entity.getSourceRef()))
                .map(EntityState::getEntityId)
                .toList();
        if (entityIds.isEmpty()) {
            return List.of();
        }
        return flowRepository.findAllByOrderByNameAsc().stream()
                .filter(flow -> mentionsAny(flow, entityIds))
                .map(flow -> new FlowReferenceResponse(flow.getId(), flow.getName(), flow.isEnabled()))
                .toList();
    }

    private static boolean mentionsAny(Flow flow, List<String> entityIds) {
        String draft = flow.getDraftDefinition() != null ? flow.getDraftDefinition() : "";
        String deployed = flow.getDeployedDefinition() != null ? flow.getDeployedDefinition() : "";
        return entityIds.stream().anyMatch(id -> draft.contains(id) || deployed.contains(id));
    }
}
