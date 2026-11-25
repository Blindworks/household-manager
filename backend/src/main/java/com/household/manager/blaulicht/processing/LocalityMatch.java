package com.household.manager.blaulicht.processing;

/** Erkannter Ort eines Falls und seine Grund-Relevanz (Hauptort/Ortsteil hoch, Nachbarort niedrig). */
public record LocalityMatch(String locality, int relevance, boolean nearby) {
}
