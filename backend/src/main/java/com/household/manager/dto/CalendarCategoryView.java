package com.household.manager.dto;

import com.household.manager.model.entity.CalendarCategory;

/**
 * Die Kategorie, wie sie in Termin-Antworten eingebettet mitgeliefert wird — damit das
 * Monatsraster ohne Nachschlagen rendert und ein Termin mit inzwischen deaktivierter
 * Kategorie weiterhin in seiner Farbe erscheint.
 */
public record CalendarCategoryView(Long id, String key, String name, String color, String icon) {

    public static CalendarCategoryView of(CalendarCategory category) {
        return new CalendarCategoryView(category.getId(), category.getKey(),
                category.getName(), category.getColor(), category.getIcon());
    }
}
