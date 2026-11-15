package com.household.manager.grocery.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Schnäppchen samt Stand der Angebote (jüngster Sammelzeitpunkt, null ohne Angebote). */
public record DealsView(LocalDateTime collectedAt, List<DealDto> deals) {
}
