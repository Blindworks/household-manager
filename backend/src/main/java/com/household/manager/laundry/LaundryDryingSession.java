package com.household.manager.laundry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Ein Waschgang mit Trocknungsverlauf im Keller; {@code version} schuetzt vor Doppelklicks. */
@Entity
@Table(name = "laundry_drying_session")
@Getter
@Setter
@NoArgsConstructor
public class LaundryDryingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LaundrySessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    private LaundryTrigger trigger;

    @Column(name = "asked_at", nullable = false)
    private LocalDateTime askedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "baseline_humidity")
    private Double baselineHumidity;

    @Column(name = "peak_excess")
    private Double peakExcess;

    @Column(name = "accumulated_load")
    private Double accumulatedLoad;

    @Column(name = "last_accumulated_at")
    private LocalDateTime lastAccumulatedAt;

    @Column(name = "curve_low_since")
    private LocalDateTime curveLowSince;

    @Column(name = "curve_criterion_at")
    private LocalDateTime curveCriterionAt;

    @Column(name = "curve_criterion_load")
    private Double curveCriterionLoad;

    @Column(name = "maybe_dry_at")
    private LocalDateTime maybeDryAt;

    @Column(name = "dry_question_open", nullable = false)
    private boolean dryQuestionOpen;

    @Column(name = "dry_question_at")
    private LocalDateTime dryQuestionAt;

    @Column(name = "lower_bound_load")
    private Double lowerBoundLoad;

    @Column(name = "confirmed_load")
    private Double confirmedLoad;

    @Column(name = "weak_load")
    private Double weakLoad;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Version
    private Long version;
}
