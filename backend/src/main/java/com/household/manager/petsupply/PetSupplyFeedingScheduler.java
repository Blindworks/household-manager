package com.household.manager.petsupply;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stoesst minuetlich die Verbuchung faelliger Fuetterungen an — fuer alle
 * Vorraete. Die eigentliche Logik (Marke, Nachholen, Transaktion) liegt im
 * PetSupplyService; die Scheduled-Methode wirft nie (Muster der uebrigen
 * Poller). fixedDelay statt fixedRate: Laeufe ueberlappen sich nie, die Marken
 * brauchen keine Synchronisation.
 * <p>
 * Der Property-Name bleibt petfood.*, damit eine bereits gesetzte
 * Umgebungsvariable nicht still wirkungslos wird.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PetSupplyFeedingScheduler {

    private final PetSupplyService petSupplyService;

    @Scheduled(fixedDelayString = "${petfood.feeding.check-interval-ms:60000}")
    public void checkDueFeedings() {
        try {
            petSupplyService.applyDueFeedings();
        } catch (Exception ex) {
            log.error("Fuetterungsabzug fehlgeschlagen — naechster Lauf holt nach", ex);
        }
    }
}
