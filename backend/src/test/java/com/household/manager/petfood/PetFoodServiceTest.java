package com.household.manager.petfood;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.PetFoodStock;
import com.household.manager.model.entity.PetFoodTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PetFoodServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private com.household.manager.repository.PetFoodStockRepository stockRepository;
    @Mock
    private com.household.manager.repository.PetFoodTransactionRepository transactionRepository;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private AuditService auditService;
    @Captor
    private ArgumentCaptor<PetFoodTransaction> txCaptor;
    @Captor
    private ArgumentCaptor<EntityStateUpdate> updateCaptor;

    private PetFoodService service;
    private PetFoodStock stock;

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(at(2026, 8, 15, 16, 30), BERLIN);
        service = new PetFoodService(stockRepository, transactionRepository,
                entityStateService, auditService, clock);
        stock = PetFoodStock.builder()
                .id(PetFoodStock.SINGLETON_ID)
                .cansRemaining(new BigDecimal("10.0"))
                .targetCans(new BigDecimal("48.0"))
                .deductionMarker(at(2026, 8, 15, 16, 5))
                .build();
        // lenient: die reinen Validierungstests (0,5-Raster, null) werfen vor dem
        // ersten Repository-Zugriff — ein strikter Stub wuerde dort als unnoetig gelten.
        lenient().when(stockRepository.findById(PetFoodStock.SINGLETON_ID))
                .thenReturn(Optional.of(stock));
    }

    @Test
    void nullMarkeSetztMarkeOhneAbzug() {
        stock.setDeductionMarker(null);

        service.applyDueFeedings();

        assertThat(stock.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 16, 30));
        assertThat(stock.getCansRemaining()).isEqualByComparingTo("10.0");
        verify(transactionRepository, never()).save(any());
        // Erstinbetriebnahme spiegelt sofort, damit der Sensor direkt existiert.
        verify(entityStateService).reportState(any());
    }

    @Test
    void uhrRuecksprungSpultDieMarkeNichtZurueck() {
        // Marke liegt NACH der (zurueckgesprungenen) Test-Uhr von 16:30 — z. B. 17:00.
        stock.setDeductionMarker(at(2026, 8, 15, 17, 0));

        service.applyDueFeedings();

        assertThat(stock.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 17, 0));
        assertThat(stock.getCansRemaining()).isEqualByComparingTo("10.0");
        verify(transactionRepository, never()).save(any());
        verify(stockRepository, never()).save(any());
    }

    @Test
    void holtVerpassteFuetterungenNach() {
        // Marke 25,5 h zurueck: 16:00 (14.8.), 7:00 und 16:00 (15.8.) sind faellig.
        stock.setDeductionMarker(at(2026, 8, 14, 15, 0));

        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("8.5");
        assertThat(stock.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 16, 30));
        verify(transactionRepository, org.mockito.Mockito.times(3)).save(txCaptor.capture());
        assertThat(txCaptor.getAllValues())
                .allSatisfy(tx -> {
                    assertThat(tx.getType()).isEqualTo(PetFoodTransaction.Type.FEEDING);
                    assertThat(tx.getAmount()).isEqualByComparingTo("-0.5");
                });
        // occurred_at ist der Fuetterungszeitpunkt, nicht die Scheduler-Laufzeit.
        assertThat(txCaptor.getAllValues().get(0).getOccurredAt())
                .isEqualTo(ZonedDateTime.ofInstant(at(2026, 8, 14, 16, 0), BERLIN).toLocalDateTime());
    }

    @Test
    void bestandKlemmtBeiNull() {
        stock.setCansRemaining(new BigDecimal("0.3"));
        stock.setDeductionMarker(at(2026, 8, 15, 15, 0)); // genau eine Fuetterung faellig (16:00)

        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("0.0");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("-0.3");
    }

    @Test
    void keineFaelligeFuetterungRuecktNurDieMarkeVor() {
        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("10.0");
        verify(transactionRepository, never()).save(any());
        // Auch ohne Abzug wird gespeichert (Marke) — aber kein Entity-Update noetig.
        verify(stockRepository).save(stock);
    }

    @Test
    void fuetterungSpiegeltDieEntitaet() {
        stock.setDeductionMarker(at(2026, 8, 15, 15, 0));

        service.applyDueFeedings();

        verify(entityStateService).reportState(updateCaptor.capture());
        EntityStateUpdate update = updateCaptor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.pet_food_toni_cans");
        assertThat(update.state()).isEqualTo("9.5");
    }

    @Test
    void einkaufBuchtZuUndSchreibtJournal() {
        service.recordPurchase(new BigDecimal("24"), "Karton");

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("34.0");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(PetFoodTransaction.Type.PURCHASE);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("24");
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("petfood.purchase"), any());
    }

    @Test
    void einkaufLehntNichtHalbeSchritteAb() {
        assertThatThrownBy(() -> service.recordPurchase(new BigDecimal("0.3"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void einkaufLehntNullUndNegativAb() {
        assertThatThrownBy(() -> service.recordPurchase(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordPurchase(new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void korrekturSetztAbsolutUndJournalisiertDieDifferenz() {
        service.correctStock(new BigDecimal("8.5"), "gezaehlt");

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("8.5");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(PetFoodTransaction.Type.CORRECTION);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("-1.5");
    }

    @Test
    void korrekturOhneAenderungSchreibtKeinJournal() {
        service.correctStock(new BigDecimal("10.0"), null);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void statusRechnetProzentUndReichweite() {
        PetFoodDtos.StatusResponse status = service.getStatus();

        assertThat(status.cansRemaining()).isEqualByComparingTo("10.0");
        assertThat(status.percent()).isEqualTo(21); // 10/48 gerundet
        assertThat(status.daysRemaining()).isEqualTo(10);
    }

    @Test
    void einkaufSpiegeltGlattenBestandOhneNachkommastellen() {
        service.recordPurchase(new BigDecimal("24"), null);

        verify(entityStateService).reportState(updateCaptor.capture());
        // stripTrailingZeros/toPlainString-Vertrag: 34.0 wird "34", nie "34.0" —
        // wichtig fuer numerische Flow-Vergleiche.
        assertThat(updateCaptor.getValue().state()).isEqualTo("34");
    }

    @Test
    void zielbestandHappyPathSpiegeltUndAuditiert() {
        PetFoodDtos.StatusResponse status = service.updateTarget(new BigDecimal("60"));

        assertThat(status.targetCans()).isEqualByComparingTo("60");
        verify(entityStateService).reportState(updateCaptor.capture());
        assertThat(updateCaptor.getValue().attributes().get("percent")).isEqualTo(17); // 10/60 gerundet
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("petfood.target.update"), any());
    }

    @Test
    void journalLimitWirdGekappt() {
        service.getTransactions(999);
        service.getTransactions(0);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageCaptor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(transactionRepository, org.mockito.Mockito.times(2))
                .findByOrderByOccurredAtDescIdDesc(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues().get(0).getPageSize()).isEqualTo(200);
        assertThat(pageCaptor.getAllValues().get(1).getPageSize()).isEqualTo(1);
    }

    @Test
    void zielbestandLehntNullUndNichtPositivesAb() {
        assertThatThrownBy(() -> service.updateTarget(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateTarget(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
