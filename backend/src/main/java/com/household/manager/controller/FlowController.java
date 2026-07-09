package com.household.manager.controller;

import com.household.manager.dto.*;
import com.household.manager.flowengine.DebugBuffer;
import com.household.manager.flowengine.FlowService;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.ValidationResult;
import com.household.manager.model.entity.Flow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * REST-API der Flow-Engine (CRUD, Deploy, Inject, Debug, Node-Katalog).
 */
@RestController
@RequestMapping("/v1/flows")
public class FlowController {

    private final FlowService flowService;
    private final DebugBuffer debugBuffer;
    private final List<NodeHandler> handlers;

    public FlowController(FlowService flowService, DebugBuffer debugBuffer, List<NodeHandler> handlers) {
        this.flowService = flowService;
        this.debugBuffer = debugBuffer;
        this.handlers = handlers;
    }

    @GetMapping
    public List<FlowSummaryResponse> getFlows() {
        return flowService.getAll().stream().map(this::toSummary).toList();
    }

    @PostMapping
    public FlowDetailResponse createFlow(@RequestBody CreateFlowRequest request) {
        return toDetail(flowService.create(request.name(), request.description()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlowDetailResponse> getFlow(@PathVariable Long id) {
        return flowService.getById(id)
                .map(flow -> ResponseEntity.ok(toDetail(flow)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public FlowDetailResponse updateFlow(@PathVariable Long id, @RequestBody UpdateFlowRequest request) {
        return toDetail(flowService.update(id, request.name(), request.description(), request.draftDefinition()));
    }

    @PostMapping("/{id}/deploy")
    public ResponseEntity<ValidationResult> deploy(@PathVariable Long id) {
        ValidationResult result = flowService.deploy(id);
        return result.valid() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/{id}/enable")
    public FlowSummaryResponse enable(@PathVariable Long id) {
        return toSummary(flowService.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    public FlowSummaryResponse disable(@PathVariable Long id) {
        return toSummary(flowService.setEnabled(id, false));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlow(@PathVariable Long id) {
        flowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/nodes/{nodeId}/inject")
    public ResponseEntity<Void> inject(@PathVariable Long id, @PathVariable String nodeId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null && body.get("payload") instanceof Map<?, ?> p
                ? castPayload(p) : Map.of();
        flowService.inject(id, nodeId, payload);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/nodes/{nodeId}/debug")
    public List<DebugBuffer.DebugEntry> debugEntries(@PathVariable Long id, @PathVariable String nodeId) {
        return debugBuffer.entries(id, nodeId);
    }

    @GetMapping("/node-types")
    public List<NodeTypeResponse> nodeTypes() {
        return handlers.stream()
                .map(handler -> NodeTypeResponse.builder()
                        .type(handler.type())
                        .outputPorts(handler.outputPorts())
                        .trigger(handler instanceof TriggerNodeHandler)
                        .configSchema(handler.configSchema())
                        .build())
                .sorted(Comparator.comparing(NodeTypeResponse::type))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPayload(Map<?, ?> payload) {
        return (Map<String, Object>) payload;
    }

    private FlowSummaryResponse toSummary(Flow flow) {
        return FlowSummaryResponse.builder()
                .id(flow.getId()).name(flow.getName()).description(flow.getDescription())
                .enabled(flow.isEnabled()).deployed(flow.getDeployedDefinition() != null)
                .deployedAt(flow.getDeployedAt()).updatedAt(flow.getUpdatedAt())
                .build();
    }

    private FlowDetailResponse toDetail(Flow flow) {
        return FlowDetailResponse.builder()
                .id(flow.getId()).name(flow.getName()).description(flow.getDescription())
                .enabled(flow.isEnabled()).deployed(flow.getDeployedDefinition() != null)
                .draftDefinition(flow.getDraftDefinition()).deployedDefinition(flow.getDeployedDefinition())
                .deployedAt(flow.getDeployedAt()).createdAt(flow.getCreatedAt()).updatedAt(flow.getUpdatedAt())
                .build();
    }
}
