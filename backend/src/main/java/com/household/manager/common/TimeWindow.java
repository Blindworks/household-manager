package com.household.manager.common;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Einzige Definition von „Uhrzeit liegt in einem Tagesfenster" — gefragt vom
 * Modus-Schnellzugriff ({@code ModeQuickAccessResolver}) und vom Flow-Node
 * {@code time-condition}, damit beide nie verschieden urteilen.
 *
 * <p>Halboffenes Intervall {@code [from, to)}: der Beginn gehoert dazu, das Ende nicht.
 * Liegt {@code to} vor {@code from}, ueberspannt das Fenster Mitternacht
 * ({@code 22:00}–{@code 06:00}). Gleicher Beginn und gleiches Ende ergeben ein
 * <b>leeres</b> Fenster — nie ein volles: „nie" und „immer" waeren sonst nicht
 * unterscheidbar, und eine von Hand eingetragene Zeile darf keinen Dauerzustand erzeugen.
 */
public record TimeWindow(LocalTime from, LocalTime to) {

    public TimeWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public boolean isEmpty() {
        return from.equals(to);
    }

    public boolean contains(LocalTime time) {
        if (isEmpty()) {
            return false;
        }
        if (from.isBefore(to)) {
            return !time.isBefore(from) && time.isBefore(to);
        }
        return !time.isBefore(from) || time.isBefore(to);
    }
}
