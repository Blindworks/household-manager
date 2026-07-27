package com.household.manager.repository;

import com.household.manager.model.entity.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByRecurringParentId(Long recurringParentId);

    Optional<CalendarEvent> findByRecurringParentIdAndRecurrenceDate(
            Long recurringParentId, LocalDate recurrenceDate);

    void deleteByRecurringParentId(Long recurringParentId);

    /** Grundlage des Loeschschutzes: eine genutzte Kategorie darf nicht verschwinden. */
    long countByCategoryId(Long categoryId);
}
