package com.household.manager.controller;

import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import com.household.manager.service.UtilityPriceService;
import com.household.manager.service.UtilityPricingSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UtilityPriceController.class)
@AutoConfigureMockMvc(addFilters = false)
class UtilityPricingSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private UtilityPriceService utilityPriceService;
    @MockitoBean
    private UtilityPricingSettingsService settingsService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void liefertDenGasfaktor() throws Exception {
        when(settingsService.getGasKwhPerM3()).thenReturn(new BigDecimal("10.63"));
        mockMvc.perform(get("/v1/utility-prices/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gasKwhPerM3").value(10.63));
    }

    @Test
    void speichertEinenPlausiblenGasfaktor() throws Exception {
        when(settingsService.getGasKwhPerM3()).thenReturn(new BigDecimal("11.2"));
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 11.2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gasKwhPerM3").value(11.2));

        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(settingsService).saveGasKwhPerM3(captor.capture());
        // Jackson erzeugt aus dem JSON-Literal 11.2 ein BigDecimal mit Skala 1 (11.2),
        // BigDecimal.equals wuerde bei abweichender Skala (z. B. "11.20") faelschlich
        // scheitern - deshalb Wertvergleich statt Skalenvergleich.
        assertThat(captor.getValue()).isEqualByComparingTo(new BigDecimal("11.2"));
    }

    @Test
    void speichertDenGasfaktorAnDerOberenGrenze() throws Exception {
        when(settingsService.getGasKwhPerM3()).thenReturn(new BigDecimal("15"));
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 15}"))
                .andExpect(status().isOk());
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(settingsService).saveGasKwhPerM3(captor.capture());
        assertThat(captor.getValue()).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    void lehntFehlendenWertAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }

    /**
     * Seit dem Wechsel des DTO-Feldes auf {@code BigDecimal} scheitert ein
     * nicht-numerischer Wert bereits beim Deserialisieren (Jackson kann "NaN" nicht in
     * ein BigDecimal wandeln) und wird vom bestehenden
     * {@code HttpMessageNotReadableException}-Handler in {@code GlobalExceptionHandler}
     * mit 400 beantwortet - nicht mit 500.
     */
    @Test
    void lehntNichtNumerischenWertAlsUnlesbarenRequestAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": \"NaN\"}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }

    @Test
    void lehntWerteAusserhalbDesBereichsAb() throws Exception {
        mockMvc.perform(put("/v1/utility-prices/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gasKwhPerM3\": 100}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveGasKwhPerM3(any());
    }
}
