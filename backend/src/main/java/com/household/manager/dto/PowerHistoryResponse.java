package com.household.manager.dto;

import java.util.List;

/**
 * Leistungsverlauf eines Verbrauchers fuer den Graph-Dialog.
 * Punkte aufsteigend nach Zeit; ein null-Wert markiert eine Messluecke.
 */
public record PowerHistoryResponse(
        String entityId,
        String displayName,
        List<TimeValue> points
) {
}
