package com.household.manager.blaulicht.processing;

/**
 * Ein Fall aus einer (Sammel-)Meldung.
 *
 * @param placeHeader Ortsteil der Kopfzeile („Bad Vilbel“, „Rockenberg / Wölfersheim“), null ohne Kopfzeile
 * @param headline    Überschrift des Falls
 * @param body        Text des Falls (Absätze mit Leerzeile getrennt)
 */
public record BulletinCase(String placeHeader, String headline, String body) {
}
