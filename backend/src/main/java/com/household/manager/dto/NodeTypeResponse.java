package com.household.manager.dto;

import com.household.manager.flowengine.NodeFieldDescriptor;
import lombok.Builder;

import java.util.List;

@Builder
public record NodeTypeResponse(
        String type,
        int outputPorts,
        boolean trigger,
        List<String> portLabels,
        List<NodeFieldDescriptor> fields) {
}
