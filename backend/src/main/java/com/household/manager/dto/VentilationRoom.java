package com.household.manager.dto;

import java.math.BigDecimal;

/** Ein betroffener Raum der Lüftungsempfehlung. */
public record VentilationRoom(String name, BigDecimal temperature) {
}
