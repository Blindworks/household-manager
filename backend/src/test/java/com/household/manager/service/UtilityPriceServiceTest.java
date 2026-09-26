package com.household.manager.service;

import com.household.manager.dto.UtilityPriceRequest;
import com.household.manager.dto.UtilityPriceResponse;
import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UtilityPriceServiceTest {

    @Mock
    private UtilityPriceRepository repository;
    @InjectMocks
    private UtilityPriceService service;

    /**
     * Wasser war bisher ausgeschlossen (nur Strom und Gas). Fuer die Kostenansicht
     * des Tablets braucht auch Wasser einen Preis.
     */
    @Test
    void nimmtEinenWasserpreisAn() {
        when(repository.findOverlappingPrices(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> {
            UtilityPrice p = inv.getArgument(0);
            p.setId(7L);
            return p;
        });
        UtilityPriceRequest request = new UtilityPriceRequest();
        request.setMeterType(MeterType.WATER);
        request.setPrice(new BigDecimal("4.5000"));
        request.setValidFrom(LocalDate.of(2026, 1, 1));

        UtilityPriceResponse response = service.createUtilityPrice(request);

        assertThat(response.getMeterType()).isEqualTo(MeterType.WATER);
        assertThat(response.getId()).isEqualTo(7L);
    }

    /**
     * Ein kuenftiger Nachfolgetarif beendet den laufenden, unbefristeten Preis
     * zu seinem eigenen Beginn (validTo ist exklusiv, das ergibt keine Luecke).
     */
    @Test
    void beendetDenUnbefristetenVorgaengerZumBeginnDesNachfolgers() {
        UtilityPrice laufend = price(1L, MeterType.GAS, LocalDate.of(2025, 1, 1), null);
        when(repository.findByMeterTypeAndValidToIsNull(MeterType.GAS)).thenReturn(List.of(laufend));
        when(repository.findOverlappingPrices(any(), any(), any())).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createUtilityPrice(request(MeterType.GAS, LocalDate.of(2026, 11, 1), null));

        assertThat(laufend.getValidTo()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    /**
     * Ein befristeter neuer Preis darf den unbefristeten nicht beenden - nach
     * seinem Ende stuende sonst gar kein Preis mehr. Das bleibt eine Ueberschneidung.
     */
    @Test
    void befristeterNeuerPreisLaesstDenUnbefristetenStehen() {
        UtilityPrice laufend = price(1L, MeterType.GAS, LocalDate.of(2025, 1, 1), null);
        when(repository.findOverlappingPrices(any(), any(), any())).thenReturn(List.of(laufend));

        assertThatThrownBy(() -> service.createUtilityPrice(
                request(MeterType.GAS, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(laufend.getValidTo()).isNull();
    }

    /**
     * Beginnt der unbefristete Preis nicht VOR dem neuen, laesst er sich nicht
     * sinnvoll beenden (validTo <= validFrom) - dann bleibt es beim 400.
     */
    @Test
    void unbefristeterPreisAbGleichemTagBleibtEineUeberschneidung() {
        UtilityPrice laufend = price(1L, MeterType.GAS, LocalDate.of(2026, 11, 1), null);
        when(repository.findByMeterTypeAndValidToIsNull(MeterType.GAS)).thenReturn(List.of(laufend));
        when(repository.findOverlappingPrices(any(), any(), any())).thenReturn(List.of(laufend));

        assertThatThrownBy(() -> service.createUtilityPrice(
                request(MeterType.GAS, LocalDate.of(2026, 11, 1), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(laufend.getValidTo()).isNull();
    }

    private static UtilityPrice price(Long id, MeterType type, LocalDate from, LocalDate to) {
        return UtilityPrice.builder()
                .id(id)
                .meterType(type)
                .price(new BigDecimal("0.1000"))
                .validFrom(from)
                .validTo(to)
                .build();
    }

    private static UtilityPriceRequest request(MeterType type, LocalDate from, LocalDate to) {
        UtilityPriceRequest request = new UtilityPriceRequest();
        request.setMeterType(type);
        request.setPrice(new BigDecimal("0.1142"));
        request.setValidFrom(from);
        request.setValidTo(to);
        return request;
    }
}
