package com.household.manager.petfood;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.PetFoodStock;
import com.household.manager.model.entity.PetFoodTransaction;
import com.household.manager.repository.PetFoodStockRepository;
import com.household.manager.repository.PetFoodTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuehrt den Toni-Futtervorrat: automatische Fuetterungsabzuege, Einkaeufe,
 * Korrekturen, Zielbestand. Jede Bestandsaenderung spiegelt
 * {@code sensor.pet_food_toni_cans} in den Entity-State-Layer (Warnflow-Trigger).
 * <p>
 * Bewusst kein Optimistic Locking (@Version) auf pet_food_stock: Scheduler
 * (minuetlich) und REST-Schreibpfade koennten sich theoretisch ein Lost Update
 * liefern, aber bei Haushalts-Traffic (zwei Abzuege pro Tag, Einkaeufe pro
 * Woche) ist das Fenster praktisch bedeutungslos, und eine Korrektur-Buchung
 * heilt jede Abweichung. Wird der Vorrat je von mehreren Systemen beschrieben,
 * ist @Version die erste Nachruestung.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PetFoodService {

    static final String ENTITY_ID = "sensor.pet_food_toni_cans";
    private static final BigDecimal HALF_CAN = new BigDecimal("0.5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_TRANSACTIONS = 200;

    private final PetFoodStockRepository stockRepository;
    private final PetFoodTransactionRepository transactionRepository;
    private final EntityStateService entityStateService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PetFoodDtos.StatusResponse getStatus() {
        return toStatus(loadStock());
    }

    @Transactional(readOnly = true)
    public List<PetFoodDtos.TransactionResponse> getTransactions(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_TRANSACTIONS));
        return transactionRepository.findByOrderByOccurredAtDescIdDesc(PageRequest.of(0, capped))
                .stream().map(PetFoodDtos.TransactionResponse::from).toList();
    }

    /**
     * Verbucht alle Fuetterungen zwischen Marke und jetzt. Abzug, Journal und Marke
     * laufen in EINER Transaktion; schlaegt sie fehl, bleibt die Marke stehen und der
     * naechste Lauf holt nach (idempotent). Die Entity-Spiegelung am Methodenende
     * passiert vor dem Commit — ein danach scheiternder Commit liesse den Sensor bis
     * zur naechsten Aenderung zu neu aussehen; akzeptiert, weil reportState nie wirft
     * und der Fall praktisch nicht auftritt. Dazu kommt: EntityStateWriter.upsert
     * laeuft REQUIRES_NEW, ein EntityStateChangedEvent (und damit ein Warnflow) kann
     * also schon feuern, bevor der aeussere Commit sicher ist — schlimmstenfalls eine
     * verfruehte Warnung, ebenfalls akzeptiert.
     */
    @Transactional
    public void applyDueFeedings() {
        PetFoodStock stock = loadStock();
        Instant now = clock.instant();
        Instant marker = stock.getDeductionMarker();
        if (marker == null) {
            // Erstinbetriebnahme: Marke setzen, nichts abziehen — sonst wuerde ab
            // Epochenbeginn nachgeholt. Trotzdem sofort spiegeln, damit der Sensor
            // direkt nach dem ersten Scheduler-Lauf existiert (Warnflow-Anlage).
            stock.setDeductionMarker(now.truncatedTo(ChronoUnit.SECONDS));
            stockRepository.save(stock);
            mirrorEntity(stock);
            return;
        }
        List<Instant> due = FeedingSchedule.between(marker, now, clock.getZone());
        boolean changed = false;
        for (Instant feeding : due) {
            BigDecimal deduction = stock.getCansRemaining().min(HALF_CAN);
            if (deduction.signum() > 0) {
                stock.setCansRemaining(stock.getCansRemaining().subtract(deduction));
                changed = true;
            }
            transactionRepository.save(PetFoodTransaction.builder()
                    .occurredAt(LocalDateTime.ofInstant(feeding, clock.getZone()))
                    .type(PetFoodTransaction.Type.FEEDING)
                    .amount(deduction.negate())
                    .cansAfter(stock.getCansRemaining())
                    .build());
        }
        // Sekundengenau abschneiden: MariaDB wuerde Bruchsekunden in DATETIME RUNDEN
        // und koennte die Marke in die Zukunft schieben (verlorene Fuetterung).
        stock.setDeductionMarker(now.truncatedTo(ChronoUnit.SECONDS));
        stockRepository.save(stock);
        if (changed) {
            log.info("Futtervorrat: {} Fuetterung(en) verbucht, Bestand {}",
                    due.size(), stock.getCansRemaining());
            mirrorEntity(stock);
        }
    }

    @Transactional
    public PetFoodDtos.StatusResponse recordPurchase(BigDecimal cans, String note) {
        requirePresent(cans, "cans");
        if (cans.signum() <= 0) {
            throw new IllegalArgumentException("Die Dosenzahl muss groesser als 0 sein.");
        }
        requireHalfSteps(cans, "cans");
        PetFoodStock stock = loadStock();
        stock.setCansRemaining(stock.getCansRemaining().add(cans));
        transactionRepository.save(PetFoodTransaction.builder()
                .occurredAt(LocalDateTime.now(clock))
                .type(PetFoodTransaction.Type.PURCHASE)
                .amount(cans)
                .cansAfter(stock.getCansRemaining())
                .note(note)
                .build());
        stockRepository.save(stock);
        auditService.record("petfood.purchase", cans + " Dosen zugebucht");
        mirrorEntity(stock);
        return toStatus(stock);
    }

    @Transactional
    public PetFoodDtos.StatusResponse correctStock(BigDecimal cansRemaining, String note) {
        requirePresent(cansRemaining, "cansRemaining");
        if (cansRemaining.signum() < 0) {
            throw new IllegalArgumentException("Der Bestand kann nicht negativ sein.");
        }
        requireHalfSteps(cansRemaining, "cansRemaining");
        PetFoodStock stock = loadStock();
        BigDecimal diff = cansRemaining.subtract(stock.getCansRemaining());
        if (diff.signum() == 0) {
            return toStatus(stock);
        }
        stock.setCansRemaining(cansRemaining);
        transactionRepository.save(PetFoodTransaction.builder()
                .occurredAt(LocalDateTime.now(clock))
                .type(PetFoodTransaction.Type.CORRECTION)
                .amount(diff)
                .cansAfter(stock.getCansRemaining())
                .note(note)
                .build());
        stockRepository.save(stock);
        auditService.record("petfood.correction", "Bestand korrigiert auf " + cansRemaining);
        mirrorEntity(stock);
        return toStatus(stock);
    }

    @Transactional
    public PetFoodDtos.StatusResponse updateTarget(BigDecimal targetCans) {
        requirePresent(targetCans, "targetCans");
        if (targetCans.signum() <= 0) {
            throw new IllegalArgumentException("Der Zielbestand muss groesser als 0 sein.");
        }
        requireHalfSteps(targetCans, "targetCans");
        PetFoodStock stock = loadStock();
        stock.setTargetCans(targetCans);
        stockRepository.save(stock);
        auditService.record("petfood.target.update", "Zielbestand auf " + targetCans + " gesetzt");
        mirrorEntity(stock);
        return toStatus(stock);
    }

    private PetFoodStock loadStock() {
        return stockRepository.findById(PetFoodStock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "pet_food_stock ist nicht geseedet — Liquibase-Migration fehlt."));
    }

    /**
     * BigDecimal statt double macht die NaN-Falle strukturell unmoeglich (Jackson
     * lehnt Nicht-Zahlen fuer BigDecimal mit 400 ab); zu pruefen bleiben nur
     * null, Vorzeichen und das 0,5-Raster. Reihenfolge in den Aufrufern: null,
     * dann Vorzeichen, dann Raster — sonst bekaeme z. B. -0,3 die Raster- statt
     * der treffenderen Vorzeichen-Meldung.
     */
    private static void requirePresent(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Feld '" + field + "' fehlt.");
        }
    }

    private static void requireHalfSteps(BigDecimal value, String field) {
        if (value.remainder(HALF_CAN).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                    "Feld '" + field + "' muss ein Vielfaches von 0,5 sein.");
        }
    }

    private PetFoodDtos.StatusResponse toStatus(PetFoodStock stock) {
        // Guard nur gegen Hand-Edits in der DB erreichbar (API lehnt targetCans <= 0
        // ab) — aber ohne ihn waere dann jeder GET ein 500er.
        int percent = stock.getTargetCans().signum() <= 0
                ? 0
                : stock.getCansRemaining()
                        .multiply(HUNDRED)
                        .divide(stock.getTargetCans(), 0, RoundingMode.HALF_UP)
                        .intValue();
        int daysRemaining = stock.getCansRemaining().setScale(0, RoundingMode.FLOOR).intValue();
        return new PetFoodDtos.StatusResponse(
                stock.getCansRemaining(), stock.getTargetCans(), percent, daysRemaining);
    }

    private void mirrorEntity(PetFoodStock stock) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("targetCans", stock.getTargetCans());
        attributes.put("percent", toStatus(stock).percent());
        attributes.put("unit", "Dosen");
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.PET_FOOD)
                .sourceRef("toni")
                .friendlyName("Toni-Futtervorrat")
                .state(stock.getCansRemaining().stripTrailingZeros().toPlainString())
                .attributes(attributes)
                .build());
    }
}
