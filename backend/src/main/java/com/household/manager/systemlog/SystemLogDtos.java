package com.household.manager.systemlog;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public final class SystemLogDtos {

    private SystemLogDtos() {
    }

    public record SystemLogEntryDto(long id, LocalDateTime occurredAt, SystemLogKind kind, SystemLogStatus status,
                                    String source, String title, String message, JsonNode details,
                                    Long flowId, String runId, int repeatCount) { }

    public record FlowRef(long id, String name) { }
}
