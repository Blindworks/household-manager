package com.household.manager.petsupply;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PetSupply;
import com.household.manager.model.entity.PetSupplyTransaction;
import com.household.manager.repository.PetSupplyRepository;
import com.household.manager.repository.PetSupplyTransactionRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PetSupplyServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private PetSupplyRepository supplyRepository;
    @Mock
    private PetSupplyTransactionRepository transactionRepository;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private AuditService auditService;
    @Captor
    private ArgumentCaptor<PetSupplyTransaction> txCaptor;
    @Captor
    private ArgumentCaptor<EntityStateUpdate> updateCaptor;

    private PetSupplyService service;
    private PetSupply food;
    private PetSupply tablets;

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    private static PetSupply supply(long id, String key, String name, String unit,
                                    String amount, String target, String perFeeding, String step) {
        return PetSupply.builder()
                .id(id)
                .supplyKey(key)
                .name(name)
                .unit(unit)
                .amountRemaining(new BigDecimal(amount))
                .targetAmount(new BigDecimal(target))
                .perFeeding(new BigDecimal(perFeeding))
                .stepSize(new BigDecimal(step))
                .displayOrder((int) id)
                .build();
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(at(2026, 8, 15, 17, 0), BERLIN);
        service = new PetSupplyService(supplyRepository, transactionRepository,
                entityStateService, auditService, clock);
        food = supply(1L, "toni_cans", "Futtervorrat", "Dosen", "30", "48", "0.5", "0.5");
        tablets = supply(2L, "toni_vomisan", "VomiSan-Tabletten", "Tabletten", "30", "60", "1", "1");
    }

    private void supplies(PetSupply... all) {
        lenient().when(supplyRepository.findAllByOrderByDisplayOrderAscIdAsc())
                .thenReturn(List.of(all));
        for (PetSupply s : all) {
            lenient().when(supplyRepository.findBySupplyKey(s.getSupplyKey()))
                    .thenReturn(Optional.of(s));
        }
        lenient().when(supplyRepository.findBySupplyKey("gibtsnicht")).thenReturn(Optional.empty());
    }

    @Test
    void ersterLaufEinesNeuenVorratsSetztNurDieMarke() {
        supplies(tablets);
        tablets.setDeductionMarker(null);

        service.applyDueFeedings();

        assertThat(tablets.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 17, 0));
        assertThat(tablets.getAmountRemaining()).isEqualByComparingTo("30");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void jederVorratZiehtSeineEigeneMengeAb() {
        supplies(food, tablets);
        // Marke vor der Morgenfuetterung, jetzt nach der Nachmittagsfuetterung:
        // zwei faellige Zeitpunkte fuer beide Vorraete.
        food.setDeductionMarker(at(2026, 8, 15, 6, 0));
        tablets.setDeductionMarker(at(2026, 8, 15, 6, 0));

        service.applyDueFeedings();

        assertThat(food.getAmountRemaining()).isEqualByComparingTo("29");
        assertThat(tablets.getAmountRemaining()).isEqualByComparingTo("28");
    }

    @Test
    void einFehlerAnEinemVorratStopptDieAnderenNicht() {
        supplies(food, tablets);
        food.setDeductionMarker(at(2026, 8, 15, 6, 0));
        tablets.setDeductionMarker(at(2026, 8, 15, 6, 0));
        when(supplyRepository.save(food)).thenThrow(new RuntimeException("DB weg"));

        service.applyDueFeedings();

        assertThat(tablets.getAmountRemaining()).isEqualByComparingTo("28");
    }

    @Test
    void uhrRuecksprungUeberspringtDenLauf() {
        supplies(food);
        food.setDeductionMarker(at(2026, 8, 16, 8, 0));

        service.applyDueFeedings();

        assertThat(food.getAmountRemaining()).isEqualByComparingTo("30");
        assertThat(food.getDeductionMarker()).isEqualTo(at(2026, 8, 16, 8, 0));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void derAbzugWirdAmBestandGekappt() {
        supplies(tablets);
        tablets.setAmountRemaining(new BigDecimal("1"));
        tablets.setDeductionMarker(at(2026, 8, 15, 6, 0));

        service.applyDueFeedings();

        assertThat(tablets.getAmountRemaining()).isEqualByComparingTo("0");
    }

    @Test
    void tablettenRasterLehntHalbeStueckeAb() {
        supplies(tablets);

        assertThatThrownBy(() -> service.recordPurchase("toni_vomisan", new BigDecimal("2.5"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vielfaches von 1");
    }

    @Test
    void dosenRasterErlaubtHalbeDosen() {
        supplies(food);

        service.recordPurchase("toni_cans", new BigDecimal("2.5"), null);

        assertThat(food.getAmountRemaining()).isEqualByComparingTo("32.5");
    }

    @Test
    void reichweiteRechnetMitDemTagesverbrauchDesVorrats() {
        supplies(food, tablets);
        food.setAmountRemaining(new BigDecimal("7"));
        tablets.setAmountRemaining(new BigDecimal("7"));

        List<PetSupplyDtos.SupplyResponse> responses = service.getSupplies();

        assertThat(responses.get(0).daysRemaining()).isEqualTo(7);
        assertThat(responses.get(0).perDay()).isEqualByComparingTo("1.0");
        assertThat(responses.get(1).daysRemaining()).isEqualTo(3);
        assertThat(responses.get(1).perDay()).isEqualByComparingTo("2");
    }

    @Test
    void entityIdDesFuttersBleibtUnveraendert() {
        supplies(food);

        service.recordPurchase("toni_cans", new BigDecimal("1"), null);

        verify(entityStateService).reportState(updateCaptor.capture());
        assertThat(updateCaptor.getValue().entityId()).isEqualTo("sensor.pet_food_toni_cans");
    }

    @Test
    void tablettenBekommenEineEigeneEntity() {
        supplies(tablets);

        service.recordPurchase("toni_vomisan", new BigDecimal("10"), null);

        verify(entityStateService).reportState(updateCaptor.capture());
        EntityStateUpdate update = updateCaptor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.pet_food_toni_vomisan");
        assertThat(update.state()).isEqualTo("40");
        assertThat(update.attributes()).containsEntry("unit", "Tabletten");
        assertThat(update.attributes()).containsEntry("daysRemaining", 20);
    }

    @Test
    void unbekannterSchluesselWirftResourceNotFound() {
        supplies(food);

        assertThatThrownBy(() -> service.recordPurchase("gibtsnicht", new BigDecimal("1"), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void korrekturBuchtDieDifferenz() {
        supplies(tablets);

        service.correctStock("toni_vomisan", new BigDecimal("42"), "gezaehlt");

        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("12");
        assertThat(txCaptor.getValue().getAmountAfter()).isEqualByComparingTo("42");
        assertThat(txCaptor.getValue().getSupply()).isSameAs(tablets);
    }
}
