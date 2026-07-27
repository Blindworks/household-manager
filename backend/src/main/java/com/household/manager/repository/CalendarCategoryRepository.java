package com.household.manager.repository;

import com.household.manager.model.entity.CalendarCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarCategoryRepository extends JpaRepository<CalendarCategory, Long> {

    /** Anzeigereihenfolge des Admin-Bereichs und der Auswahlliste. */
    List<CalendarCategory> findAllByOrderBySortOrderAscNameAsc();
}
