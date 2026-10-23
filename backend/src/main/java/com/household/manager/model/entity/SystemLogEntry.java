package com.household.manager.model.entity;

import com.household.manager.systemlog.SystemLogKind;
import com.household.manager.systemlog.SystemLogStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Eine Zeile des Systemprotokolls. */
@Entity
@Table(name = "system_log_entry")
@Getter @Setter @NoArgsConstructor
public class SystemLogEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SystemLogKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SystemLogStatus status;

    @Column(nullable = false, length = 200)
    private String source;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String message;

    @Column(columnDefinition = "LONGTEXT")
    private String details;

    @Column(name = "flow_id")
    private Long flowId;

    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "repeat_count", nullable = false)
    private int repeatCount = 1;
}
