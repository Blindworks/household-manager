package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryPriceObservationRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryRetentionJobTest {

    private final GroceryOfferRepository offers = mock(GroceryOfferRepository.class);
    private final GroceryPriceObservationRepository observations = mock(GroceryPriceObservationRepository.class);
    private final GroceryMatchRepository matches = mock(GroceryMatchRepository.class);
    private final SystemJobMonitor monitor = mock(SystemJobMonitor.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T02:35:00Z"), ZoneId.of("Europe/Berlin"));
    private final GroceryRetentionJob job = new GroceryRetentionJob(offers, observations, matches, monitor, clock);

    @Test
    void deletesWithCutoffsAndKeepsUserVerdicts() {
        job.run();

        verify(offers).deleteExpiredBefore(LocalDate.of(2026, 10, 18));
        verify(offers).deleteUndatedCollectedBefore(LocalDateTime.of(2026, 10, 18, 3, 35));
        verify(observations).deleteObservedBefore(LocalDateTime.of(2025, 11, 15, 3, 35));
        verify(matches).deleteDecidedBefore(GroceryMatch.DecidedBy.CLAUDE, LocalDateTime.of(2025, 11, 15, 3, 35));
    }

    @Test
    void oneFailingTableDoesNotStopTheOthers() {
        when(offers.deleteExpiredBefore(any())).thenThrow(new RuntimeException("DB weg"));

        job.run();

        verify(offers).deleteUndatedCollectedBefore(any());
        verify(observations).deleteObservedBefore(any());
        verify(matches).deleteDecidedBefore(any(), any());
        verify(monitor).reportFailure("Aufräumen fehlgeschlagen: Angebote");
    }
}
