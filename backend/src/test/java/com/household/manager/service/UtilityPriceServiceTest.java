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
}
