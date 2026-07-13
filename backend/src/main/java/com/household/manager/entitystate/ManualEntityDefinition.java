package com.household.manager.entitystate;

import lombok.Builder;

import java.util.List;

/**
 * Fachliche Beschreibung einer anzulegenden manuellen Entität – entkoppelt die
 * Service-Schicht von der Web-DTO. Welche Felder relevant sind, hängt vom {@link #domain()} ab:
 * <ul>
 *     <li>INPUT_BOOLEAN – nur {@code state} ("on"/"off")</li>
 *     <li>INPUT_NUMBER  – {@code min}/{@code max}/{@code step}/{@code unit}</li>
 *     <li>INPUT_TEXT    – freier {@code state}</li>
 *     <li>INPUT_SELECT  – {@code options} (Pflicht), {@code state} eine davon</li>
 * </ul>
 */
@Builder
public record ManualEntityDefinition(
        EntityDomain domain,
        String name,
        String state,
        String icon,
        List<String> options,
        Double min,
        Double max,
        Double step,
        String unit
) {
}
