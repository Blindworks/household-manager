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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein stuendliches Cloudflare-Speedtest-Ergebnis.
 */
@Entity
@Table(name = "network_speedtest_result")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkSpeedtestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tested_at", nullable = false)
    private LocalDateTime testedAt;

    @Column(name = "download_mbps", precision = 9, scale = 2)
    private BigDecimal downloadMbps;

    @Column(name = "upload_mbps", precision = 9, scale = 2)
    private BigDecimal uploadMbps;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
