package com.household.manager.petsupply;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PetSupply;
import com.household.manager.model.entity.PetSupplyTransaction;
import com.household.manager.repository.PetSupplyRepository;
import com.household.manager.repository.PetSupplyTransactionRepository;
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
 * Fuehrt die Vorraete fuer Toni: Futter und VomiSan-Tabletten, kuenftig
 * beliebige weitere. Automatische Abzuege zu den Fuetterungszeiten, Einkaeufe,
 * Korrekturen, Zielbestand. Jede Bestandsaenderung spiegelt den Vorrat als
 * {@code sensor.pet_food_<supplyKey>} in den Entity-State-Layer
 * (Warnflow-Trigger).
 * <p>
 * Bewusst kein Optimistic Locking (@Version) auf pet_supply: Scheduler
 * (minuetlich) und REST-Schreibpfade koennten sich theoretisch ein Lost Update
 * liefern, aber bei Haushalts-Traffic (zwei Abzuege pro Tag, Einkaeufe pro
 * Woche) ist das Fenster praktisch bedeutungslos, und eine Korrektur-Buchung
 * heilt jede Abweichung. Wird ein Vorrat je von mehreren Systemen beschrieben,
 * ist @Version die erste Nachruestung.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PetSupplyService {

    /**
     * Praefix der Entity-Ids. Bleibt bewusst {@code pet_food}, obwohl das Modul
     * inzwischen allgemeiner ist: zusammen mit dem Schluessel {@code toni_cans}
     * ergibt es buchstaeblich die bestehende Id sensor.pet_food_toni_cans. Ein
     * Umbenennen wuerde jeden darauf gebauten Flow still ins Leere laufen lassen.
     */
    private static final String ENTITY_ID_PREFIX = "sensor.pet_food_";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_TRANSACTIONS = 200;

    private final PetSupplyRepository supplyRepository;
    private final PetSupplyTransactionRepository transactionRepository;
    private final EntityStateService entityStateService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PetSupplyDtos.SupplyResponse> getSupplies() {
        return supplyRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PetSupplyDtos.TransactionResponse> getTransactions(String supplyKey, int limit) {
        PetSupply supply = requireSupply(supplyKey);
        int capped = Math.max(1, Math.min(limit, MAX_TRANSACTIONS));
        return transactionRepository
                .findBySupplyOrderByOccurredAtDescIdDesc(supply, PageRequest.of(0, capped))
                .stream().map(PetSupplyDtos.TransactionResponse::from).toList();
    }

    /**
     * Verbucht faellige Fuetterungen fuer ALLE Vorraete. Ein Fehler an einem
     * Vorrat darf die anderen nicht mitreissen (Muster TractivePollingService:
     * Fehler je Objekt isoliert) — sonst liesse ein kaputter Vorrat den Abzug
     * des anderen dauerhaft ausfallen, ohne dass es jemandem auffiele.
     */
    @Transactional
    public void applyDueFeedings() {
        Instant now = clock.instant();
        for (PetSupply supply : supplyRepository.findAllByOrderByDisplayOrderAscIdAsc()) {
            try {
                applyDueFeedings(supply, now);
            } catch (Exception ex) {
                log.error("Vorrat {}: Fuetterungsabzug fehlgeschlagen — naechster Lauf holt nach",
                        supply.getSupplyKey(), ex);
            }
        }
    }

    /**
     * Verbucht alle Fuetterungen eines Vorrats zwischen seiner Marke und jetzt.
     * Abzug, Journal und Marke laufen in EINER Transaktion; schlaegt sie fehl,
     * bleibt die Marke stehen und der naechste Lauf holt nach (idempotent). Die
     * Entity-Spiegelung passiert vor dem Commit — ein danach scheiternder Commit
     * liesse den Sensor bis zur naechsten Aenderung zu neu aussehen; akzeptiert,
     * weil reportState nie wirft und der Fall praktisch nicht auftritt. Dazu
     * kommt: EntityStateWriter.upsert laeuft REQUIRES_NEW, ein
     * EntityStateChangedEvent (und damit ein Warnflow) kann also schon feuern,
     * bevor der aeussere Commit sicher ist — schlimmstenfalls eine verfruehte
     * Warnung, ebenfalls akzeptiert.
     */
    private void applyDueFeedings(PetSupply supply, Instant now) {
        Instant marker = supply.getDeductionMarker();
        if (marker == null) {
            // Erstinbetriebnahme dieses Vorrats: Marke setzen, nichts abziehen — sonst
            // wuerde ab Epochenbeginn nachgeholt. Genau deshalb ist die Marke je Vorrat
            // eigen: ein spaeter ergaenzter Vorrat darf nicht ab dem Deploy des ersten
            // leergebucht werden. Trotzdem sofort spiegeln, damit der Sensor direkt nach
            // dem ersten Scheduler-Lauf existiert (Warnflow-Anlage).
            supply.setDeductionMarker(now.truncatedTo(ChronoUnit.SECONDS));
            supplyRepository.save(supply);
            mirrorEntity(supply);
            return;
        }
        if (now.isBefore(marker)) {
            // Uhr-Ruecksprung (NTP-Korrektur/VM-Resume) darf die Marke nie zurueckspulen,
            // sonst wuerde eine bereits verbuchte Fuetterung doppelt abgezogen. Nichts tun,
            // bis die Uhr die Marke wieder eingeholt hat (Muster: Tractive-Zukunfts-Clamp).
            log.warn("Vorrat {}: Uhr ({}) liegt vor der Abzugsmarke ({}) — Lauf uebersprungen",
                    supply.getSupplyKey(), now, marker);
            return;
        }
        List<Instant> due = FeedingSchedule.between(marker, now, clock.getZone());
        boolean changed = false;
        for (Instant feeding : due) {
            BigDecimal deduction = supply.getAmountRemaining().min(supply.getPerFeeding());
            if (deduction.signum() > 0) {
                supply.setAmountRemaining(supply.getAmountRemaining().subtract(deduction));
                changed = true;
            }
            transactionRepository.save(PetSupplyTransaction.builder()
                    .supply(supply)
                    .occurredAt(LocalDateTime.ofInstant(feeding, clock.getZone()))
                    .type(PetSupplyTransaction.Type.FEEDING)
                    .amount(deduction.negate())
                    .amountAfter(supply.getAmountRemaining())
                    .build());
        }
        // Sekundengenau abschneiden: MariaDB wuerde Bruchsekunden in DATETIME RUNDEN
        // und koennte die Marke in die Zukunft schieben (verlorene Fuetterung).
        supply.setDeductionMarker(now.truncatedTo(ChronoUnit.SECONDS));
        supplyRepository.save(supply);
        if (changed) {
            log.info("Vorrat {}: {} Fuetterung(en) verbucht, Bestand {} {}",
                    supply.getSupplyKey(), due.size(), supply.getAmountRemaining(), supply.getUnit());
            mirrorEntity(supply);
        }
    }

    @Transactional
    public PetSupplyDtos.SupplyResponse recordPurchase(String supplyKey, BigDecimal amount, String note) {
        PetSupply supply = requireSupply(supplyKey);
        requirePresent(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Die Menge muss groesser als 0 sein.");
        }
        requireStep(amount, supply.getStepSize(), "amount");
        supply.setAmountRemaining(supply.getAmountRemaining().add(amount));
        transactionRepository.save(PetSupplyTransaction.builder()
                .supply(supply)
                .occurredAt(LocalDateTime.now(clock))
                .type(PetSupplyTransaction.Type.PURCHASE)
                .amount(amount)
                .amountAfter(supply.getAmountRemaining())
                .note(note)
                .build());
        supplyRepository.save(supply);
        auditService.record("petfood.purchase",
                supply.getName() + ": " + plain(amount) + " " + supply.getUnit() + " zugebucht");
        mirrorEntity(supply);
        return toResponse(supply);
    }

    @Transactional
    public PetSupplyDtos.SupplyResponse correctStock(String supplyKey, BigDecimal amountRemaining, String note) {
        PetSupply supply = requireSupply(supplyKey);
        requirePresent(amountRemaining, "amountRemaining");
        if (amountRemaining.signum() < 0) {
            throw new IllegalArgumentException("Der Bestand kann nicht negativ sein.");
        }
        requireStep(amountRemaining, supply.getStepSize(), "amountRemaining");
        BigDecimal diff = amountRemaining.subtract(supply.getAmountRemaining());
        if (diff.signum() == 0) {
            return toResponse(supply);
        }
        supply.setAmountRemaining(amountRemaining);
        transactionRepository.save(PetSupplyTransaction.builder()
                .supply(supply)
                .occurredAt(LocalDateTime.now(clock))
                .type(PetSupplyTransaction.Type.CORRECTION)
                .amount(diff)
                .amountAfter(supply.getAmountRemaining())
                .note(note)
                .build());
        supplyRepository.save(supply);
        auditService.record("petfood.correction",
                supply.getName() + ": Bestand korrigiert auf " + plain(amountRemaining)
                        + " " + supply.getUnit());
        mirrorEntity(supply);
        return toResponse(supply);
    }

    @Transactional
    public PetSupplyDtos.SupplyResponse updateTarget(String supplyKey, BigDecimal targetAmount) {
        PetSupply supply = requireSupply(supplyKey);
        requirePresent(targetAmount, "targetAmount");
        if (targetAmount.signum() <= 0) {
            throw new IllegalArgumentException("Der Zielbestand muss groesser als 0 sein.");
        }
        requireStep(targetAmount, supply.getStepSize(), "targetAmount");
        supply.setTargetAmount(targetAmount);
        supplyRepository.save(supply);
        auditService.record("petfood.target.update",
                supply.getName() + ": Zielbestand auf " + plain(targetAmount)
                        + " " + supply.getUnit() + " gesetzt");
        mirrorEntity(supply);
        return toResponse(supply);
    }

    private PetSupply requireSupply(String supplyKey) {
        return supplyRepository.findBySupplyKey(supplyKey)
                .orElseThrow(() -> new ResourceNotFoundException("Unbekannter Vorrat: " + supplyKey));
    }

    /**
     * BigDecimal statt double macht die NaN-Falle strukturell unmoeglich (Jackson
     * lehnt Nicht-Zahlen fuer BigDecimal mit 400 ab); zu pruefen bleiben nur
     * null, Vorzeichen und das Raster. Reihenfolge in den Aufrufern: null, dann
     * Vorzeichen, dann Raster — sonst bekaeme z. B. -0,3 die Raster- statt der
     * treffenderen Vorzeichen-Meldung.
     */
    private static void requirePresent(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Feld '" + field + "' fehlt.");
        }
    }

    /**
     * Das Raster kommt aus dem Vorrat, nicht aus einer Konstante: halbe Dosen
     * sind erlaubt, halbe Tabletten nicht.
     */
    private static void requireStep(BigDecimal value, BigDecimal step, String field) {
        if (value.remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                    "Feld '" + field + "' muss ein Vielfaches von " + plain(step) + " sein.");
        }
    }

    /** Tagesverbrauch: die Menge je Fuetterung mal der Zahl der Fuetterungszeiten. */
    private static BigDecimal perDay(PetSupply supply) {
        return supply.getPerFeeding()
                .multiply(BigDecimal.valueOf(FeedingSchedule.FEEDING_TIMES.size()));
    }

    private PetSupplyDtos.SupplyResponse toResponse(PetSupply supply) {
        return new PetSupplyDtos.SupplyResponse(
                supply.getSupplyKey(),
                supply.getName(),
                supply.getUnit(),
                supply.getAmountRemaining(),
                supply.getTargetAmount(),
                supply.getStepSize(),
                perDay(supply),
                percent(supply),
                daysRemaining(supply));
    }

    private static int percent(PetSupply supply) {
        // Guard nur gegen Hand-Edits in der DB erreichbar (die API lehnt targetAmount <= 0
        // ab) — aber ohne ihn waere dann jeder GET ein 500er.
        if (supply.getTargetAmount().signum() <= 0) {
            return 0;
        }
        return supply.getAmountRemaining().multiply(HUNDRED)
                .divide(supply.getTargetAmount(), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private static int daysRemaining(PetSupply supply) {
        BigDecimal perDay = perDay(supply);
        if (perDay.signum() <= 0) {
            return 0;
        }
        return supply.getAmountRemaining().divide(perDay, 0, RoundingMode.FLOOR).intValue();
    }

    static String entityId(PetSupply supply) {
        return ENTITY_ID_PREFIX + supply.getSupplyKey();
    }

    /** "34" statt "34.0" — der Entity-State ist Text, und 34.0 laese sich schlechter. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private void mirrorEntity(PetSupply supply) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("targetAmount", supply.getTargetAmount());
        attributes.put("percent", percent(supply));
        attributes.put("daysRemaining", daysRemaining(supply));
        attributes.put("perDay", perDay(supply));
        attributes.put("unit", supply.getUnit());
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId(supply))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.PET_FOOD)
                .sourceRef(supply.getSupplyKey())
                .friendlyName(supply.getName())
                .state(plain(supply.getAmountRemaining()))
                .attributes(attributes)
                .build());
    }
}
