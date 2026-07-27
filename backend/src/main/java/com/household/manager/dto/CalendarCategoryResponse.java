package com.household.manager.dto;

import com.household.manager.model.entity.CalendarCategory;

/** Eine Kategorie inklusive Verwaltungsfeldern, wie die Admin-Seite sie zeigt. */
public record CalendarCategoryResponse(Long id, String key, String name, String color,
                                       String icon, int sortOrder, boolean active) {

    public static CalendarCategoryResponse of(CalendarCategory category) {
        return new CalendarCategoryResponse(category.getId(), category.getKey(),
                category.getName(), category.getColor(), category.getIcon(),
                category.getSortOrder(), category.isActive());
    }
}
