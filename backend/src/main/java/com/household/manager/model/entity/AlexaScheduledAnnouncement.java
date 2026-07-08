package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/** Zeitgeplante Ansage: Text zu einer Uhrzeit an ausgewaehlten Wochentagen. */
@Entity
@Table(name = "alexa_scheduled_announcement")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaScheduledAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    /** Wochentage als CSV der java.time.DayOfWeek-Namen, z. B. "MONDAY,TUESDAY". */
    @Column(name = "weekdays", nullable = false, length = 128)
    private String weekdays;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private com.household.manager.alexa.AlexaTtsMode mode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_run")
    private LocalDateTime lastRun;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "alexa_scheduled_announcement_device",
            joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "serial_number", nullable = false, length = 128)
    @Builder.Default
    private Set<String> targetSerialNumbers = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
