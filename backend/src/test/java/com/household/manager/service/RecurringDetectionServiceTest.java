package com.household.manager.service;

import com.household.manager.dto.RecurringDetectionResponse;
import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.finance.DetectedSeries;
import com.household.manager.finance.RecurringBooking;
import com.household.manager.finance.RecurringMerchantKey;
import com.household.manager.finance.RecurringSeriesDetector;
import com.household.manager.model.entity.RecurrenceInterval;
import com.household.manager.model.entity.RecurringPayment;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.RecurringPaymentRepository;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringDetectionServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Long ACCOUNT = 1L;

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private RecurringPaymentRepository recurringRepository;
    @Mock
    private RecurringMerchantKey merchantKey;
    @Mock
    private RecurringSeriesDetector detector;

    private RecurringDetectionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, BERLIN).toInstant(), BERLIN);
        service = new RecurringDetectionService(transactionRepository, recurringRepository,
                merchantKey, detector, clock);
        lenient().when(recurringRepository.save(any(RecurringPayment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionRepository.findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
                eq(ACCOUNT), any(), any())).thenReturn(List.of());
    }

    @Test
    void createsNewCandidate() {
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of());
        when(detector.detect(anyList())).thenReturn(List.of(series("SPOTIFY", "-10.99", List.of("-10.99"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        ArgumentCaptor<RecurringPayment> saved = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurringRepository).save(saved.capture());
        RecurringPayment rp = saved.getValue();
        assertThat(rp.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(rp.getCounterpartyPattern()).isEqualTo("SPOTIFY");
        assertThat(rp.getExpectedAmount()).isEqualByComparingTo("-10.99");
        assertThat(rp.isConfirmed()).isFalse();
        assertThat(rp.isDismissed()).isFalse();
        assertThat(rp.getLastSeenDate()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(rp.getNextDueDate()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(rp.getCategoryId()).isEqualTo(7L);
        assertThat(response.getCreated()).isEqualTo(1);
        assertThat(response.getUpdated()).isZero();
    }

    @Test
    void updatesExistingWithinTenPercent() {
        RecurringPayment stored = stored(10L, "CLAUDE", "-29.80", true, false);
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(stored));
        when(detector.detect(anyList())).thenReturn(List.of(
                series("CLAUDE", "-84.80", List.of("-29.80", "-84.80"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        ArgumentCaptor<RecurringPayment> saved = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurringRepository, times(1)).save(saved.capture());
        RecurringPayment rp = saved.getValue();
        assertThat(rp).isSameAs(stored);
        assertThat(rp.getExpectedAmount()).isEqualByComparingTo("-84.80");
        assertThat(rp.getLastSeenDate()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(rp.getNextDueDate()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(rp.isConfirmed()).isTrue();
        assertThat(response.getCreated()).isZero();
        assertThat(response.getUpdated()).isEqualTo(1);
    }

    @Test
    void dismissedStaysDismissedAfterPriceIncrease() {
        RecurringPayment stored = stored(11L, "CLAUDE", "-29.80", false, true);
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(stored));
        when(detector.detect(anyList())).thenReturn(List.of(
                series("CLAUDE", "-84.80", List.of("-29.80", "-84.80"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        ArgumentCaptor<RecurringPayment> saved = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurringRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(stored);
        assertThat(stored.isDismissed()).isTrue();
        assertThat(stored.getExpectedAmount()).isEqualByComparingTo("-84.80");
        assertThat(response.getCreated()).isZero();
        assertThat(response.getUpdated()).isEqualTo(1);
    }

    @Test
    void parallelContractsMatchTheirOwnRow() {
        RecurringPayment cheap = stored(20L, "DRILLISCH", "-4.99", true, false);
        RecurringPayment pricey = stored(21L, "DRILLISCH", "-7.99", true, false);
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(pricey, cheap));
        // Price of the cheap contract rose slightly; both series still find their own row.
        when(detector.detect(anyList())).thenReturn(List.of(
                series("DRILLISCH", "-7.99", List.of("-7.99")),
                series("DRILLISCH", "-5.29", List.of("-4.99", "-5.29"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        assertThat(pricey.getExpectedAmount()).isEqualByComparingTo("-7.99");
        assertThat(cheap.getExpectedAmount()).isEqualByComparingTo("-5.29");
        verify(recurringRepository, times(2)).save(any(RecurringPayment.class));
        assertThat(response.getCreated()).isZero();
        assertThat(response.getUpdated()).isEqualTo(2);
    }

    @Test
    void eachStoredRowIsConsumedAtMostOnce() {
        RecurringPayment only = stored(30L, "DRILLISCH", "-4.99", true, false);
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(only));
        when(detector.detect(anyList())).thenReturn(List.of(
                series("DRILLISCH", "-4.99", List.of("-4.99")),
                series("DRILLISCH", "-5.09", List.of("-5.09"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        assertThat(only.getExpectedAmount()).isEqualByComparingTo("-4.99");
        assertThat(response.getUpdated()).isEqualTo(1);
        assertThat(response.getCreated()).isEqualTo(1);
    }

    @Test
    void differentIntervalDoesNotMatch() {
        RecurringPayment stored = stored(40L, "RUNDFUNK", "-55.08", true, false);
        stored.setInterval(RecurrenceInterval.QUARTERLY);
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of(stored));
        when(detector.detect(anyList())).thenReturn(List.of(series("RUNDFUNK", "-55.08", List.of("-55.08"))));

        RecurringDetectionResponse response = service.detect(ACCOUNT);

        assertThat(response.getCreated()).isEqualTo(1);
        assertThat(response.getUpdated()).isZero();
    }

    @Test
    void listHidesDismissedAndMarksEnded() {
        RecurringPayment ended = stored(1L, "RAILWAY", "-5.00", true, false);
        ended.setLastSeenDate(LocalDate.of(2026, 4, 2));
        ended.setNextDueDate(LocalDate.of(2026, 5, 2));
        RecurringPayment active = stored(2L, "SPOTIFY", "-10.99", true, false);
        active.setLastSeenDate(LocalDate.of(2026, 10, 28));
        active.setNextDueDate(LocalDate.of(2026, 11, 28));
        RecurringPayment activeNoDate = stored(3L, "LEGACY", "-1.00", true, false);
        activeNoDate.setLastSeenDate(null);
        activeNoDate.setNextDueDate(null);
        when(recurringRepository.findByDismissedFalse()).thenReturn(List.of(ended, activeNoDate, active));

        List<RecurringPaymentResponse> list = service.list(null);

        assertThat(list).extracting(RecurringPaymentResponse::getId).containsExactly(2L, 3L, 1L);
        assertThat(list).extracting(RecurringPaymentResponse::isEnded).containsExactly(false, false, true);
        assertThat(list.get(0).getLastSeenDate()).isEqualTo(LocalDate.of(2026, 10, 28));
        verify(recurringRepository, never()).findAll();
    }

    @Test
    void listWithConfirmedFilterExcludesDismissed() {
        when(recurringRepository.findByConfirmedAndDismissedFalse(true)).thenReturn(List.of());

        service.list(true);

        verify(recurringRepository).findByConfirmedAndDismissedFalse(true);
    }

    @Test
    void endedThresholdIsOneAndAHalfIntervals() {
        RecurringPayment boundary = stored(1L, "X", "-5.00", true, false);
        boundary.setLastSeenDate(TODAY.minusDays(45));
        RecurringPayment past = stored(2L, "Y", "-5.00", true, false);
        past.setLastSeenDate(TODAY.minusDays(46));
        when(recurringRepository.findByDismissedFalse()).thenReturn(List.of(boundary, past));

        List<RecurringPaymentResponse> list = service.list(null);

        assertThat(list).extracting(RecurringPaymentResponse::getId, RecurringPaymentResponse::isEnded)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, false),
                        org.assertj.core.groups.Tuple.tuple(2L, true));
    }

    @Test
    void dismissSetsFlagInsteadOfDeleting() {
        RecurringPayment rp = stored(5L, "SPOTIFY", "-10.99", false, false);
        when(recurringRepository.findById(5L)).thenReturn(Optional.of(rp));

        service.dismiss(5L);

        ArgumentCaptor<RecurringPayment> saved = ArgumentCaptor.forClass(RecurringPayment.class);
        verify(recurringRepository).save(saved.capture());
        assertThat(saved.getValue().isDismissed()).isTrue();
        verify(recurringRepository, never()).deleteById(anyLong());
        verify(recurringRepository, never()).delete(any(RecurringPayment.class));
    }

    @Test
    void dismissUnknownIdThrows() {
        when(recurringRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismiss(99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsIncomeAndKeyless() {
        Transaction expense = tx("-10.99", "Spotify AB", "abo");
        Transaction income = tx("2500.00", "Arbeitgeber", "Gehalt");
        Transaction keyless = tx("-3.00", null, "bar");
        when(transactionRepository.findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
                ACCOUNT, TODAY.minusYears(2), TODAY)).thenReturn(List.of(expense, income, keyless));
        when(merchantKey.keyFor("Spotify AB", "abo")).thenReturn(Optional.of("SPOTIFY AB"));
        when(merchantKey.keyFor(null, "bar")).thenReturn(Optional.empty());
        when(recurringRepository.findByAccountId(ACCOUNT)).thenReturn(List.of());
        when(detector.detect(anyList())).thenReturn(List.of());

        service.detect(ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecurringBooking>> bookings = ArgumentCaptor.forClass(List.class);
        verify(detector).detect(bookings.capture());
        assertThat(bookings.getValue()).containsExactly(new RecurringBooking(
                TODAY.minusDays(3), new BigDecimal("-10.99"), "SPOTIFY AB", 7L));
        verify(merchantKey, never()).keyFor(eq("Arbeitgeber"), anyString());
    }

    private static DetectedSeries series(String key, String expected, List<String> amounts) {
        List<BigDecimal> values = new ArrayList<>();
        amounts.forEach(a -> values.add(new BigDecimal(a)));
        return new DetectedSeries(key, RecurrenceInterval.MONTHLY, new BigDecimal(expected),
                LocalDate.of(2025, 1, 28), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 28),
                7L, values);
    }

    private static RecurringPayment stored(Long id, String key, String amount, boolean confirmed, boolean dismissed) {
        return RecurringPayment.builder()
                .id(id).accountId(ACCOUNT).counterpartyPattern(key)
                .expectedAmount(new BigDecimal(amount)).interval(RecurrenceInterval.MONTHLY)
                .confirmed(confirmed).dismissed(dismissed)
                .build();
    }

    private static Transaction tx(String amount, String counterparty, String purpose) {
        return Transaction.builder()
                .accountId(ACCOUNT).bookingDate(TODAY.minusDays(3)).amount(new BigDecimal(amount))
                .counterpartyName(counterparty).purpose(purpose).categoryId(7L)
                .build();
    }
}
