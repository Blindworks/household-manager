package com.household.manager.systemlog;

import java.time.LocalDateTime;

/** Ein Protokollereignis, bevor es der {@link SystemLogWriter} in eine Zeile uebersetzt. */
public record SystemLogEvent(LocalDateTime occurredAt, SystemLogKind kind, SystemLogStatus status,
                             String source, String title, String message, String detailsJson,
                             Long flowId, String runId) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LocalDateTime occurredAt;
        private SystemLogKind kind;
        private SystemLogStatus status;
        private String source;
        private String title;
        private String message;
        private String detailsJson;
        private Long flowId;
        private String runId;

        public Builder occurredAt(LocalDateTime v) { this.occurredAt = v; return this; }
        public Builder kind(SystemLogKind v) { this.kind = v; return this; }
        public Builder status(SystemLogStatus v) { this.status = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder detailsJson(String v) { this.detailsJson = v; return this; }
        public Builder flowId(Long v) { this.flowId = v; return this; }
        public Builder runId(String v) { this.runId = v; return this; }

        public SystemLogEvent build() {
            return new SystemLogEvent(occurredAt, kind, status, source, title, message, detailsJson, flowId, runId);
        }
    }
}
