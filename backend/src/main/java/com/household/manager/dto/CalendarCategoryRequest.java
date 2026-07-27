package com.household.manager.dto;

/**
 * Anlege- und Aenderungsdaten einer Kalender-Kategorie. Der Schluessel fehlt bewusst:
 * er wird beim Anlegen erzeugt und ist danach unveraenderlich.
 *
 * <p>{@code active} ist ein Wrapper, kein primitives boolean: aus einem fehlenden
 * JSON-Feld machte Jackson sonst {@code false} und legte stillschweigend eine
 * deaktivierte Kategorie an, die in keiner Auswahlliste auftaucht. Der Service liest
 * {@code null} als "aktiv" — so gilt derselbe Default wie in Entity und Spalte.
 * {@code sortOrder} bleibt primitiv; die 0 ist dort ein brauchbarer Default.
 */
public record CalendarCategoryRequest(String name, String color, String icon,
                                      int sortOrder, Boolean active) {
}
