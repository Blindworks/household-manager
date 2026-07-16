package com.household.manager.service;

import java.time.LocalDate;

/** Ein aus dem ICS gelesener Abholtermin, noch ohne Datenbankbezug. */
public record ParsedWasteEvent(LocalDate date, String label) {
}
