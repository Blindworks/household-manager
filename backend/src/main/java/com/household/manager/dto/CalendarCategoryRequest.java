package com.household.manager.dto;

/**
 * Anlege- und Aenderungsdaten einer Kalender-Kategorie. Der Schluessel fehlt bewusst:
 * er wird beim Anlegen erzeugt und ist danach unveraenderlich.
 */
public record CalendarCategoryRequest(String name, String color, String icon,
                                      int sortOrder, boolean active) {
}
