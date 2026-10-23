package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Ein Flow-Lauf, der an einem Delay-/Timer-Node wartet (ueberlebt einen Neustart als Spur). */
@Entity
@Table(name = "system_flow_pending_run")
@Getter @Setter @NoArgsConstructor
public class SystemFlowPendingRun {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "flow_id", nullable = false)
    private long flowId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "flow_name", length = 200)
    private String flowName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
