package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Ein Termin bzw. eine Serie (rrule gesetzt) des Haushaltskalenders.
 * Override-Zeilen (recurringParentId gesetzt) ersetzen genau ein Serien-Vorkommen;
 * geloeschte Einzelvorkommen stehen als EXDATE-Daten in {@link #exdates}.
 */
@Entity
@Table(name = "calendar_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CalendarCategory category;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** Fuer mehrtaegige ganztaegige Termine. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** iCal-RRULE; null = Einzeltermin. */
    @Column(length = 500)
    private String rrule;

    /** Kommagetrennte ISO-Daten geloeschter Einzelvorkommen (EXDATE). */
    @Column(columnDefinition = "TEXT")
    private String exdates;

    @Column(name = "recurring_parent_id")
    private Long recurringParentId;

    /** Welches Serien-Vorkommen diese Override-Zeile ersetzt. */
    @Column(name = "recurrence_date")
    private LocalDate recurrenceDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isRecurring() {
        return rrule != null && !rrule.isBlank();
    }

    public boolean isOverride() {
        return recurringParentId != null;
    }

    public Set<LocalDate> exdateSet() {
        if (exdates == null || exdates.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(exdates.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }

    public void addExdate(LocalDate date) {
        Set<LocalDate> all = new TreeSet<>(exdateSet());
        all.add(date);
        exdates = all.stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }

    /**
     * Entfernt ein EXDATE (z.B. wenn ein Override dasselbe Datum uebernimmt - der
     * Override gewinnt, ein Datum darf nie gleichzeitig geloescht und geaendert sein).
     * Wirkungslos, wenn das Datum nicht enthalten ist; bleibt nichts mehr uebrig, wird
     * {@link #exdates} auf null gesetzt statt auf einen leeren String.
     */
    public void removeExdate(LocalDate date) {
        Set<LocalDate> all = new TreeSet<>(exdateSet());
        all.remove(date);
        exdates = all.isEmpty() ? null : all.stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }
}
