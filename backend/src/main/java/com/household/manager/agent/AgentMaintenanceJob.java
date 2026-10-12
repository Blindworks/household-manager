package com.household.manager.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Minuetliche Pflege des Hausverstands: verfallene Vorschlaege schliessen, in der Ruhezeit
 * zurueckgehaltene Vorschlaege nachreichen, abgelaufene Notizen loeschen.
 *
 * <p>Jeder Schritt steht in einem eigenen try/catch: die drei sind voneinander unabhaengig, und ein
 * DB-Fehler beim Verfallen darf das Nachreichen nicht mit ausknipsen — sonst bliebe ein morgens
 * faelliger Vorschlag unbemerkt liegen. Die Methode wirft nie.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentMaintenanceJob {

    private final AgentProposalService proposalService;
    private final AgentNoteService noteService;

    @Scheduled(fixedDelay = 60_000)
    public void minute() {
        step("Vorschlaege verfallen lassen", proposalService::expireStale);
        step("Vorschlaege nachreichen", proposalService::deliverPending);
        step("Notizen aufraeumen", noteService::purgeExpired);
    }

    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("Hausverstand-Wartung '{}' fehlgeschlagen", name, ex);
        }
    }
}
