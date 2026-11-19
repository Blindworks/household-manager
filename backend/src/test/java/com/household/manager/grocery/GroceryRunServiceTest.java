package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.OfferCollectionService.CollectionResult;
import com.household.manager.grocery.dto.RunResultDto;
import com.household.manager.grocery.dto.RunStatusDto;
import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.CandidatePrefilter;
import com.household.manager.grocery.matching.ClaudeOfferJudge;
import com.household.manager.grocery.matching.MatchingService;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.MatchingService.MatchingResult;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.grocery.pricing.DealEvaluator;
import com.household.manager.grocery.pricing.PriceObservationRecorder;
import com.household.manager.grocery.pricing.UsualPriceCalculator;
import com.household.manager.grocery.pricing.UsualPriceCalculator.UsualPrice;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryRunServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final UsualPrice USUAL = new UsualPrice(new BigDecimal("2.000"), 8, 4, false);

    /** Uhr, die der Test vorstellen kann. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-15T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return BERLIN;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final OfferCollectionService collector = mock(OfferCollectionService.class);
    private final GroceryProductRepository products = mock(GroceryProductRepository.class);
    private final GroceryOfferRepository offers = mock(GroceryOfferRepository.class);
    private final MatchingService matching = mock(MatchingService.class);
    private final PriceObservationRecorder recorder = mock(PriceObservationRecorder.class);
    private final UsualPriceCalculator usualPrices = mock(UsualPriceCalculator.class);
    private final DealEvaluator evaluator = mock(DealEvaluator.class);
    private final GrocerySettingsService settings = mock(GrocerySettingsService.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final GroceryEntityReporter reporter = mock(GroceryEntityReporter.class);
    private final NormalPriceEstimates estimates = mock(NormalPriceEstimates.class);

    @BeforeEach
    void setUp() {
        when(settings.dealThresholdPercent()).thenReturn(10);
        when(collector.collect()).thenReturn(new CollectionResult(5, Map.of()));
        when(offers.findActiveFrom(TODAY)).thenReturn(List.of());
        when(matching.matchAll(anyList(), anyList(), anyInt()))
                .thenReturn(new MatchingResult(Map.of(), 0, 0, 0, 0, 0));
    }

    private GroceryRunService service(MatchingService matchingService, DealEvaluator dealEvaluator) {
        return service(matchingService, dealEvaluator, Runnable::run);
    }

    private GroceryRunService service(MatchingService matchingService, DealEvaluator dealEvaluator,
                                      java.util.concurrent.Executor manualExecutor) {
        return new GroceryRunService(collector, products, offers, matchingService, recorder, usualPrices,
                dealEvaluator, estimates, settings, properties, reporter, clock, manualExecutor);
    }

    private static GroceryProduct product(long id, String search) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setName("P" + id);
        p.setBaseUnit(BaseUnit.LITER);
        p.setSearchWords(search);
        p.setMustWords("");
        p.setExcludeWords("");
        p.setActive(true);
        return p;
    }

    private static GroceryOffer offer(String title, String fingerprint) {
        GroceryOffer o = new GroceryOffer();
        o.setChain(Chain.PENNY);
        o.setTitle(title);
        o.setPrice(new BigDecimal("0.86"));
        o.setBasePrice(new BigDecimal("1.43"));
        o.setBaseUnit(BaseUnit.LITER);
        o.setValidTo(TODAY.plusDays(5));
        o.setFingerprint(fingerprint);
        o.setCollectedAt(LocalDateTime.of(2026, 11, 15, 4, 12));
        return o;
    }

    @Test
    void runContinuesWhenOneProductMatchingFails() {
        GroceryProduct a = product(1L, "cola");
        GroceryProduct b = product(2L, "milch");
        GroceryOffer offer = offer("Milch", "fp-b");
        MatchedOffer matchB = new MatchedOffer(offer, Verdict.MATCH, null, null, false);
        Deal dealB = new Deal(matchB, new BigDecimal("1.43"), new BigDecimal("2.000"), Deal.ReferenceKind.USUAL, 29,
                false, false, null);
        when(products.findByActiveTrue()).thenReturn(List.of(a, b));
        when(products.findAll()).thenReturn(List.of(a, b));
        when(offers.findActiveFrom(TODAY)).thenReturn(List.of(offer));
        when(matching.matchAll(List.of(a, b), List.of(offer), 50)).thenReturn(new MatchingResult(
                Map.of(1L, List.of(), 2L, List.of(matchB)), 2, 0, 100, 20, 1));
        when(usualPrices.usual(1L, TODAY)).thenThrow(new RuntimeException("DB weg"));
        when(usualPrices.usual(2L, TODAY)).thenReturn(USUAL);
        when(evaluator.evaluate(b, List.of(matchB), USUAL, 10)).thenReturn(List.of(dealB));

        GroceryRunService service = service(matching, evaluator);

        RunResultDto result = service.run();

        assertThat(result.deals()).isEqualTo(1);
        assertThat(result.offers()).isEqualTo(5);
        assertThat(result.aiCalls()).isEqualTo(2);
        assertThat(result.aiFailures()).isEqualTo(1);
        assertThat(service.snapshot().deals()).containsEntry(2L, List.of(dealB)).containsEntry(1L, List.of());
        verify(recorder).record(b, List.of(matchB));
        verify(reporter).report(Map.of(1L, List.of(), 2L, List.of(dealB)), List.of(a, b));
    }

    @Test
    void runStoresSnapshotAndReportsSourceFailures() {
        GroceryProduct b = product(2L, "milch");
        when(products.findByActiveTrue()).thenReturn(List.of(b));
        when(collector.collect()).thenReturn(new CollectionResult(3, Map.of(SourceKey.REWE, "Zugriff gesperrt (HTTP 403)")));
        GroceryRunService service = service(matching, evaluator);

        RunResultDto result = service.run();

        assertThat(result.sourceFailures()).containsEntry("REWE", "Zugriff gesperrt (HTTP 403)");
        assertThat(service.snapshot().deals()).containsKey(2L);
    }

    @Test
    void fullRunEstimatesNormalPricesAndCountsTheCalls() {
        GroceryProduct coffee = product(3L, "kaffee");
        when(products.findByActiveTrue()).thenReturn(List.of(coffee));
        when(matching.matchAll(anyList(), anyList(), anyInt()))
                .thenReturn(new MatchingResult(Map.of(), 2, 0, 0, 0, 0));
        when(estimates.refreshDue(eq(List.of(coffee)), any())).thenReturn(1);

        RunResultDto result = service(matching, evaluator).run();

        verify(estimates).refreshDue(eq(List.of(coffee)), any());
        assertThat(result.aiCalls()).isEqualTo(3);
    }

    @Test
    void snapshotRebuiltAfterRestartWithoutClaude() {
        ClaudeOfferJudge judge = mock(ClaudeOfferJudge.class);
        GroceryMatchRepository matchRepo = mock(GroceryMatchRepository.class);
        MatchingService realMatching = new MatchingService(new CandidatePrefilter(), judge, matchRepo,
                products, clock);
        GroceryProduct cola = product(1L, "cola");
        GroceryOffer known = offer("Coca-Cola Zero", "fp-known");
        GroceryOffer unknown = offer("Cola Light", "fp-unknown");
        GroceryMatch stored = new GroceryMatch();
        stored.setProductId(1L);
        stored.setFingerprint("fp-known");
        stored.setVerdict(GroceryMatch.Verdict.MATCH);
        stored.setDecidedBy(GroceryMatch.DecidedBy.CLAUDE);
        when(matchRepo.findByProductId(1L)).thenReturn(List.of(stored));
        when(products.findByActiveTrue()).thenReturn(List.of(cola));
        when(products.findAll()).thenReturn(List.of(cola));
        when(offers.findActiveFrom(TODAY)).thenReturn(List.of(known, unknown));
        when(usualPrices.usual(1L, TODAY)).thenReturn(USUAL);
        GroceryRunService service = service(realMatching, new DealEvaluator(clock));

        service.rebuildFromDatabase();

        verify(judge, never()).judge(any(), anyList(), anyList());
        verify(matchRepo, never()).saveAll(anyList());
        verify(collector, never()).collect();
        verify(recorder, never()).record(any(), anyList());
        GroceryRunService.Snapshot snapshot = service.snapshot();
        assertThat(snapshot.deals().get(1L)).hasSize(1);
        assertThat(snapshot.deals().get(1L).get(0).match().offer().getFingerprint()).isEqualTo("fp-known");
        assertThat(snapshot.matches().get(1L)).hasSize(1);
        assertThat(snapshot.collectedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 4, 12));
        verify(reporter).report(eq(snapshot.deals()), eq(List.of(cola)));
    }

    @Test
    void manualRunCooldown429() {
        GroceryRunService service = service(matching, evaluator);

        service.runManually();
        assertThatThrownBy(service::runManually).isInstanceOf(TooManyRequestsException.class);
        clock.advance(Duration.ofMinutes(9));
        assertThatThrownBy(service::runManually).isInstanceOf(TooManyRequestsException.class);
        clock.advance(Duration.ofMinutes(1));
        service.runManually();

        verify(collector, times(2)).collect();
    }

    @Test
    void runDuringRunIsRejected() {
        GroceryRunService service = service(matching, evaluator);
        when(collector.collect()).thenAnswer(inv -> {
            assertThatThrownBy(service::run).isInstanceOf(TooManyRequestsException.class)
                    .hasMessage("Lauf läuft bereits");
            assertThatThrownBy(service::runManually).isInstanceOf(TooManyRequestsException.class);
            return new CollectionResult(0, Map.of());
        });

        service.run();

        verify(collector, times(1)).collect();
        // Die abgelehnte manuelle Anfrage hat keinen Mindestabstand verbraucht
        service.runManually();
    }

    @Test
    void manualRunStartsInBackgroundAndBlocksFurtherRunsUntilDone() {
        List<Runnable> queued = new java.util.ArrayList<>();
        GroceryRunService service = service(matching, evaluator, queued::add);

        service.runManually();

        verify(collector, never()).collect();
        assertThat(queued).hasSize(1);
        assertThatThrownBy(service::run).isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Lauf läuft bereits");

        queued.get(0).run();

        verify(collector, times(1)).collect();
        service.run();
        verify(collector, times(2)).collect();
    }

    @Test
    void failingBackgroundRunReleasesTheRunningFlag() {
        List<Runnable> queued = new java.util.ArrayList<>();
        GroceryRunService service = service(matching, evaluator, queued::add);
        when(collector.collect()).thenThrow(new RuntimeException("Netz weg"))
                .thenReturn(new CollectionResult(0, Map.of()));

        service.runManually();
        queued.get(0).run();

        service.run();
        verify(collector, times(2)).collect();
    }

    @Test
    void runStatusIsRunningUntilTheBackgroundRunFinishes() {
        List<Runnable> queued = new java.util.ArrayList<>();
        GroceryRunService service = service(matching, evaluator, queued::add);
        assertThat(service.runStatus().running()).isFalse();
        assertThat(service.runStatus().finishedAt()).isNull();

        service.runManually();

        RunStatusDto during = service.runStatus();
        assertThat(during.running()).isTrue();
        assertThat(during.startedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 11, 0));
        assertThat(during.finishedAt()).isNull();

        clock.advance(Duration.ofMinutes(3));
        queued.get(0).run();

        RunStatusDto after = service.runStatus();
        assertThat(after.running()).isFalse();
        assertThat(after.finishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 11, 3));
        assertThat(after.lastResult()).isNotNull();
        assertThat(after.lastResult().offers()).isEqualTo(5);
        assertThat(after.lastError()).isNull();
    }

    @Test
    void failedRunRecordsTheErrorInTheStatus() {
        GroceryRunService service = service(matching, evaluator);
        when(collector.collect()).thenThrow(new IllegalStateException("DB weg"));

        assertThatThrownBy(service::run).isInstanceOf(IllegalStateException.class);

        RunStatusDto status = service.runStatus();
        assertThat(status.running()).isFalse();
        assertThat(status.finishedAt()).isNotNull();
        assertThat(status.lastResult()).isNull();
        assertThat(status.lastError()).isEqualTo("DB weg");
    }

    @Test
    void nightlyRunAlsoUpdatesTheStatus() {
        GroceryRunService service = service(matching, evaluator);

        service.run();

        assertThat(service.runStatus().finishedAt()).isNotNull();
        assertThat(service.runStatus().lastResult()).isNotNull();
    }

    @Test
    void rejectedSubmitReleasesRunningFlagAndCooldown() {
        GroceryRunService service = service(matching, evaluator, command -> {
            throw new java.util.concurrent.RejectedExecutionException("voll");
        });

        assertThatThrownBy(service::runManually).isInstanceOf(IllegalStateException.class);

        assertThat(service.runStatus().running()).isFalse();
        // Weder Sperre noch Mindestabstand bleiben hängen
        service.run();
        assertThatThrownBy(service::runManually).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshProductRecomputesWithoutClaudeBudget() {
        GroceryProduct cola = product(1L, "cola");
        when(products.findById(1L)).thenReturn(java.util.Optional.of(cola));
        when(products.findAll()).thenReturn(List.of(cola));
        when(usualPrices.usual(anyLong(), any())).thenReturn(USUAL);
        GroceryRunService service = service(matching, evaluator);

        service.refreshProduct(1L);

        verify(matching).matchAll(List.of(cola), List.of(), 0);
        verify(recorder, never()).record(any(), anyList());
        verify(estimates, never()).refreshDue(anyList(), any());
        assertThat(service.snapshot().deals()).containsKey(1L);

        when(products.findById(1L)).thenReturn(java.util.Optional.empty());
        service.refreshProduct(1L);
        assertThat(service.snapshot().deals()).doesNotContainKey(1L);
    }
}
