package com.household.manager.controller;

import com.household.manager.dto.ConsumptionPoint;
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.importer.MeterReadingCsvImporter;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import com.household.manager.service.ConsumptionRange;
import com.household.manager.service.MeterConsumptionSeriesService;
import com.household.manager.service.MeterReadingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MeterReadingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeterReadingSeriesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeterReadingService meterReadingService;
    @MockitoBean
    private MeterReadingCsvImporter meterReadingCsvImporter;
    @MockitoBean
    private MeterConsumptionSeriesService meterConsumptionSeriesService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    private void stubSeries() {
        when(meterConsumptionSeriesService.getSeries(any())).thenReturn(List.of(
                new MeterConsumptionSeries(MeterType.ELECTRICITY, "kWh", List.of(
                        new ConsumptionPoint(LocalDate.of(2026, 8, 21), "KW 34",
                                new BigDecimal("38.20"), false)))));
    }

    /**
     * "/series" und "/{type}" konkurrieren um denselben Pfad. Spring bevorzugt das
     * literale Segment - kippt das, liefert die Ansicht still einen 400
     * ("No enum constant MeterType.series"), ohne dass es jemandem auffiele.
     */
    @Test
    void liestSeriesAlsEigenenPfadUndNichtAlsZaehlertyp() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].meterType").value("ELECTRICITY"))
                .andExpect(jsonPath("$[0].unit").value("kWh"))
                .andExpect(jsonPath("$[0].points[0].label").value("KW 34"))
                .andExpect(jsonPath("$[0].points[0].periodStart").value("2026-08-21"))
                .andExpect(jsonPath("$[0].points[0].estimated").value(false));
    }

    @Test
    void nutztOhneParameterDenStandardzeitraum() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series")).andExpect(status().isOk());

        verify(meterConsumptionSeriesService).getSeries(ConsumptionRange.WEEKS_26);
    }

    @Test
    void reichtDenGewaehltenZeitraumDurch() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series?range=MONTHS_12"))
                .andExpect(status().isOk());

        verify(meterConsumptionSeriesService).getSeries(ConsumptionRange.MONTHS_12);
    }
}
