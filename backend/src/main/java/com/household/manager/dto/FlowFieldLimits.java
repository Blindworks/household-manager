package com.household.manager.dto;

/**
 * Längengrenzen der Flow-Stammdaten, gespiegelt aus den Spalten der Tabelle {@code flows}
 * (Liquibase {@code 20260709-0030}, {@code category} aus {@code 20260925-0057}). Ohne Prüfung
 * an der API-Grenze scheitert ein zu langer Wert erst beim Schreiben in die DB — und kommt beim
 * Aufrufer als nichtssagender 500 an.
 */
public final class FlowFieldLimits {

    public static final int NAME_MAX = 255;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int CATEGORY_MAX = 60;

    private FlowFieldLimits() {
    }
}
