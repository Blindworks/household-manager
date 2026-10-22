package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Letzter bekannter Stand eines geplanten Jobs; {@code state} ist OK, FAILING oder INACTIVE. */
@Entity
@Table(name = "system_job_status")
@Getter @Setter @NoArgsConstructor
public class SystemJobStatus {

    @Id
    @Column(name = "job_key", length = 200)
    private String jobKey;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "failing_since")
    private LocalDateTime failingSince;

    @Column(name = "last_message", length = 2000)
    private String lastMessage;
}
