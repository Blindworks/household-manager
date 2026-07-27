package com.household.manager.dto;

import com.household.manager.model.entity.AppUser;

/** Eine zugeordnete Person, wie Termin-Antworten sie einbetten. */
public record CalendarPersonView(Long id, String displayName) {

    public static CalendarPersonView of(AppUser user) {
        return new CalendarPersonView(user.getId(), user.getDisplayName());
    }
}
