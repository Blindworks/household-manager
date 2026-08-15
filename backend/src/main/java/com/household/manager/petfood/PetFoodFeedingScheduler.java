package com.household.manager.petfood;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stoesst minuetlich die Verbuchung faelliger Fuetterungen an. Die eigentliche
 * Logik (Marke, Nachholen, Transaktion) liegt im PetFoodService; die
 * Scheduled-Methode wirft nie (Muster der uebrigen Poller). fixedDelay statt
 * fixedRate: Laeufe ueberlappen sich nie, die Marke braucht keine Synchronisation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PetFoodFeedingScheduler {

    private final PetFoodService petFoodService;

    @Scheduled(fixedDelayString = "${petfood.feeding.check-interval-ms:60000}")
    public void checkDueFeedings() {
        try {
            petFoodService.applyDueFeedings();
        } catch (Exception ex) {
            log.error("Fuetterungsabzug fehlgeschlagen — naechster Lauf holt nach", ex);
        }
    }
}
