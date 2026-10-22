package com.household.manager.systemlog;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

public final class SystemLogDtos {

    private SystemLogDtos() {
    }

    public record SystemLogEntryDto(long id, LocalDateTime occurredAt, SystemLogKind kind, SystemLogStatus status,
                                    String source, String title, String message, JsonNode details,
                                    Long flowId, String runId, int repeatCount) { }

    public record SystemLogPageDto(List<SystemLogEntryDto> content, long totalElements, int page, int size) { }

    public record FlowRef(long id, String name) { }
}
