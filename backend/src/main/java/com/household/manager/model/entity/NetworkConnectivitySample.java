package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Eine minuetliche Internet-Erreichbarkeits-/Latenzmessung.
 */
@Entity
@Table(name = "network_connectivity_sample")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkConnectivitySample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sampled_at", nullable = false)
    private LocalDateTime sampledAt;

    @Column(name = "is_online", nullable = false)
    private boolean online;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "gateway_reachable", nullable = false)
    private boolean gatewayReachable;
}
