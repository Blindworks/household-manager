package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Zuordnung eines Termins zu einem Nutzer. Keine Zeile fuer einen Termin bedeutet
 * "betrifft den ganzen Haushalt" — das ist der Normalfall, kein fehlender Wert.
 */
@Entity
@Table(name = "calendar_event_person")
@IdClass(CalendarEventPerson.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventPerson {

    @Id
    @Column(name = "calendar_event_id", nullable = false)
    private Long calendarEventId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long calendarEventId;
        private Long userId;
    }
}
