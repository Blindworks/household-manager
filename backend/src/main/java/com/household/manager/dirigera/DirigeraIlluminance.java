package com.household.manager.dirigera;

/**
 * Einzige Umrechnung der Helligkeit des Hubs. Der MYGGSPRAY liefert den Matter-Rohwert
 * ({@code 10000 * log10(lux) + 1}, Grenzen laut Hub 1 bis 40001 = 1 bis 10 000 lx), nicht Lux:
 * gemessen 2026-11-16 abends im Treppenhaus 15186 und 9031, also rund 33 und 8 lx.
 * 0 heisst nach Matter "zu dunkel zum Messen" und wird 0 lx.
 */
final class DirigeraIlluminance {

    private DirigeraIlluminance() {
    }

    static int toLux(int raw) {
        if (raw <= 0) {
            return 0;
        }
        return (int) Math.round(Math.pow(10, (raw - 1) / 10000.0));
    }
}
