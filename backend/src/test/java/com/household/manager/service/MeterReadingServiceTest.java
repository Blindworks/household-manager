package com.household.manager.service;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.MeterReadingRequest;
import com.household.manager.dto.MeterReadingResponse;
import com.household.manager.exception.MeterReadingNotFoundException;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeterReadingServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private MeterReadingRepository repository;
    @Mock
    private AuditService auditService;

    private final List<MeterReading> stored = new ArrayList<>();
    private MeterReadingService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, BERLIN).toInstant(), BERLIN);
        service = new MeterReadingService(repository, auditService, clock);
        when(repository.findByMeterTypeOrderByReadingDateDesc(MeterType.ELECTRICITY))
                .thenAnswer(invocation -> new ArrayList<>(stored));
        when(repository.findById(any())).thenAnswer(invocation -> stored.stream()
                .filter(reading -> reading.getId().equals(invocation.getArgument(0))).findFirst());
        when(repository.save(any())).thenAnswer(invocation -> {
            MeterReading reading = invocation.getArgument(0);
            if (reading.getId() == null) {
                reading.setId(100L + stored.size());
                stored.add(reading);
            }
            return reading;
        });
    }

    @Test
    void lehntZweiteAblesungAmSelbenTagAb() {
        store(1, "2026-11-13T09:00:00", "100", false);

        assertThatThrownBy(() -> service.createMeterReading(request("2026-11-13T18:00:00", "110")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("13.11.2026");
        verify(repository, never()).save(any());
    }

    @Test
    void lehntAelteresDatumAb() {
        store(1, "2026-11-13T09:00:00", "100", false);

        assertThatThrownBy(() -> service.createMeterReading(request("2026-11-12T18:00:00", "110")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lehntZukunftUndKleinerenStandAb() {
        store(1, "2026-11-13T09:00:00", "100", false);

        assertThatThrownBy(() -> service.createMeterReading(request("2026-11-24T00:00:00", "110")))
                .hasMessageContaining("Zukunft");
        assertThatThrownBy(() -> service.createMeterReading(request("2026-11-20T09:00:00", "99")))
                .hasMessageContaining("kleiner");
    }

    @Test
    void nimmtSpaeterenTagAn() {
        store(1, "2026-11-13T18:00:00", "100", false);

        assertThat(service.createMeterReading(request("2026-11-14T08:00:00", "100")).getReading().isLatest())
                .isTrue();
    }

    @Test
    void versehentlichDoppelteAblesungLaesstSichAufRichtigesDatumKorrigieren() {
        store(1, "2026-11-13T18:00:00", "100", false);
        MeterReading duplicate = store(2, "2026-11-13T09:00:00", "110", false);

        service.correctLatestReading(2L, request("2026-11-20T09:00:00", "112"));

        assertThat(duplicate.getReadingDate()).isEqualTo(LocalDateTime.parse("2026-11-20T09:00:00"));
        assertThat(duplicate.getReadingValue()).isEqualByComparingTo("112");
        assertThat(duplicate.getReadingWeek()).isEqualTo(47);
        verify(auditService).record(eq("meter-reading.update"), anyString());
    }

    @Test
    void doppelteAblesungMitFruehererUhrzeitIstDieAktuelle() {
        MeterReading original = store(1, "2026-11-13T18:00:00", "100", false);
        MeterReading duplicate = store(2, "2026-11-13T09:00:00", "110", false);

        service.deleteLatestReading(2L);

        verify(repository).delete(duplicate);
        verify(repository, never()).delete(original);
    }

    @Test
    void korrekturPrueftGegenVorherigeAblesung() {
        store(1, "2026-11-13T09:00:00", "100", false);
        store(2, "2026-11-20T09:00:00", "110", false);

        assertThatThrownBy(() -> service.correctLatestReading(2L, request("2026-11-13T10:00:00", "110")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.correctLatestReading(2L, request("2026-11-20T09:00:00", "90")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).deleteAll(any());
    }

    @Test
    void aeltereAblesungenSindGesperrt() {
        store(1, "2026-11-13T09:00:00", "100", false);
        store(2, "2026-11-20T09:00:00", "110", false);

        assertThatThrownBy(() -> service.deleteLatestReading(1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.correctLatestReading(1L, request("2026-11-13T09:00:00", "101")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deleteLatestReading(9L))
                .isInstanceOf(MeterReadingNotFoundException.class);
    }

    @Test
    void zaehlertypLaesstSichNichtAendern() {
        store(1, "2026-11-13T09:00:00", "100", false);
        MeterReadingRequest request = request("2026-11-13T09:00:00", "100");
        request.setMeterType(MeterType.GAS);

        assertThatThrownBy(() -> service.correctLatestReading(1L, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loeschenEntferntNurAbgeleiteteSchaetzwerte() {
        store(1, "2026-10-30T09:00:00", "80", true);
        store(2, "2026-11-06T09:00:00", "90", false);
        MeterReading estimate = store(3, "2026-11-13T09:00:00", "100", true);
        MeterReading latest = store(4, "2026-11-20T09:00:00", "110", false);

        service.deleteLatestReading(4L);

        verify(repository).deleteAll(List.of(estimate));
        verify(repository).delete(latest);
        verify(auditService).record(eq("meter-reading.delete"), anyString());
    }

    @Test
    void korrekturRechnetSchaetzwerteNeu() {
        store(2, "2026-11-06T09:00:00", "90", false);
        MeterReading estimate = store(3, "2026-11-13T09:00:00", "100", true);
        MeterReading latest = store(4, "2026-11-20T09:00:00", "110", false);

        List<MeterReadingResponse> estimates =
                service.correctLatestReading(4L, request("2026-11-20T09:00:00", "130")).getAutoCreatedReadings();

        verify(repository).deleteAll(List.of(estimate));
        assertThat(estimates).singleElement()
                .satisfies(e -> assertThat(e.getReadingValue()).isEqualByComparingTo("110"));
        assertThat(latest.getReadingValue()).isEqualByComparingTo("130");
    }

    @Test
    void listeMarkiertNurDieAktuelleAblesung() {
        store(1, "2026-11-13T18:00:00", "100", false);
        store(2, "2026-11-13T09:00:00", "110", false);
        when(repository.findAllByReadingDateBetweenOrderByReadingDateDesc(any(), any()))
                .thenReturn(new ArrayList<>(stored));

        List<MeterReadingResponse> all = service.getAllMeterReadings();

        assertThat(all).extracting(MeterReadingResponse::getId).containsExactly(2L, 1L);
        assertThat(all).extracting(MeterReadingResponse::isLatest).containsExactly(true, false);
        assertThat(all.get(0).getConsumption()).isEqualByComparingTo("10");
    }

    private MeterReading store(long id, String date, String value, boolean estimated) {
        MeterReading reading = MeterReading.builder().id(id).meterType(MeterType.ELECTRICITY)
                .readingDate(LocalDateTime.parse(date)).readingValue(new BigDecimal(value))
                .estimated(estimated).build();
        stored.add(reading);
        return reading;
    }

    private MeterReadingRequest request(String date, String value) {
        return MeterReadingRequest.builder().meterType(MeterType.ELECTRICITY)
                .readingDate(LocalDateTime.parse(date)).readingValue(new BigDecimal(value)).build();
    }
}
