package com.household.manager.flowengine;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD + Deploy-Orchestrierung für Flows. Deploy validiert den Draft,
 * kopiert ihn nach deployed und registriert den Flow in der Engine-Registry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowService {

    private final FlowRepository flowRepository;
    private final FlowDefinitionParser parser;
    private final FlowValidator validator;
    private final FlowRegistry registry;
    private final FlowEngine engine;

    @Transactional(readOnly = true)
    public List<Flow> getAll() {
        return flowRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Flow> getById(Long id) {
        return flowRepository.findById(id);
    }

    @Transactional
    public Flow create(String name, String description) {
        Flow flow = Flow.builder().name(name).description(description).enabled(true)
                .draftDefinition("{ \"nodes\": [], \"wires\": [] }").build();
        return flowRepository.save(flow);
    }

    @Transactional
    public Flow update(Long id, String name, String description, String draftDefinition) {
        Flow flow = require(id);
        if (name != null) {
            flow.setName(name);
        }
        if (description != null) {
            flow.setDescription(description);
        }
        if (draftDefinition != null) {
            parser.parse(draftDefinition); // wirft IllegalArgumentException bei kaputtem JSON -> 400
            flow.setDraftDefinition(draftDefinition);
        }
        return flowRepository.save(flow);
    }

    /**
     * Legt einen Flow aus einer importierten Definition an. Der neue Flow ist
     * bewusst deaktiviert (enabled=false) und nicht deployt — scharf wird er erst
     * durch den expliziten Deploy-Schritt. Es wird nur die JSON-Struktur geprüft
     * (wie beim Draft-Speichern); die volle Graph-Validierung bleibt dem Deploy.
     */
    @Transactional
    public Flow importFlow(Integer schemaVersion, String name, String description, String definitionJson) {
        if (schemaVersion == null || schemaVersion != 1) {
            throw new IllegalArgumentException("Nicht unterstützte schemaVersion: " + schemaVersion);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name fehlt");
        }
        if (definitionJson == null || definitionJson.isBlank()) {
            throw new IllegalArgumentException("definition fehlt");
        }
        parser.parse(definitionJson); // wirft IllegalArgumentException bei kaputtem JSON -> 400
        Flow flow = Flow.builder()
                .name(name).description(description).enabled(false)
                .draftDefinition(definitionJson).build();
        return flowRepository.save(flow);
    }

    /**
     * Validiert den Draft; bei Erfolg draft -> deployed + Registry-Reload.
     * Bei Fehlern bleibt der bisherige Deploy-Stand unangetastet.
     */
    @Transactional
    public ValidationResult deploy(Long id) {
        Flow flow = require(id);
        FlowDefinition definition = parser.parse(flow.getDraftDefinition());
        ValidationResult result = validator.validate(definition);
        if (!result.valid()) {
            return result;
        }
        flow.setDeployedDefinition(flow.getDraftDefinition());
        flow.setDeployedAt(LocalDateTime.now());
        flowRepository.save(flow);
        if (flow.isEnabled()) {
            registry.deploy(id, definition);
        }
        return result;
    }

    @Transactional
    public Flow setEnabled(Long id, boolean enabled) {
        Flow flow = require(id);
        flow.setEnabled(enabled);
        flowRepository.save(flow);
        if (!enabled) {
            registry.undeploy(id);
        } else if (flow.getDeployedDefinition() != null) {
            registry.deploy(id, parser.parse(flow.getDeployedDefinition()));
        }
        return flow;
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        registry.undeploy(id);
        flowRepository.deleteById(id);
    }

    /** Feuert eine deployte Trigger-Node von Hand (Test-Inject). */
    public void inject(Long flowId, String nodeId, Map<String, Object> payload) {
        FlowGraph graph = registry.graph(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow " + flowId + " ist nicht deployt"));
        FlowNode node = graph.node(nodeId);
        if (node == null || !(registry.handler(node.type()) instanceof TriggerNodeHandler)) {
            throw new IllegalArgumentException("Node '" + nodeId + "' ist keine deployte Trigger-Node");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("timestamp", LocalDateTime.now());
        values.put("triggerNodeId", nodeId);
        if (payload != null) {
            values.putAll(payload);
        }
        engine.emitAsync(flowId, nodeId, 0, FlowMessage.of(values));
    }

    private Flow require(Long id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Flow not found with ID: " + id));
    }
}
