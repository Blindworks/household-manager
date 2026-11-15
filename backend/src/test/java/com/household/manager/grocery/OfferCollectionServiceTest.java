package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GrocerySourceRun;
import com.household.manager.grocery.source.FetchContext;
import com.household.manager.grocery.source.OfferSource;
import com.household.manager.grocery.source.RawOffer;
import com.household.manager.grocery.source.SourceFetchException;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GroceryMarketRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GrocerySourceRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfferCollectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final FetchContext CTX = new FetchContext("60311", 50.1, 8.6, List.of(), Set.of(Chain.values()));

    private final GroceryOfferRepository offers = mock(GroceryOfferRepository.class);
    private final GroceryMarketRepository markets = mock(GroceryMarketRepository.class);
    private final GrocerySourceRunRepository runs = mock(GrocerySourceRunRepository.class);
    private final FetchContextProvider contextProvider = mock(FetchContextProvider.class);
    private final GroceryProperties properties = new GroceryProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final List<GrocerySourceRun> savedRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(contextProvider.current()).thenReturn(CTX);
        when(markets.findAll()).thenReturn(List.of());
        when(runs.save(any(GrocerySourceRun.class))).thenAnswer(inv -> {
            savedRuns.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private OfferCollectionService service(OfferSource... sources) {
        return new OfferCollectionService(List.of(sources), properties, contextProvider, markets, offers, runs,
                new OfferMerger(), clock, tx);
    }

    private static OfferSource source(SourceKey key, Chain chain, String title) {
        return new FakeSource(key, true, () -> List.of(RawOffer.builder().source(key).sourceOfferId(key + "-1")
                .chain(chain).title(title).price(new BigDecimal("1.99"))
                .validFrom(TODAY).validTo(TODAY.plusDays(5)).build()));
    }

    private static OfferSource failing(SourceKey key, String reason) {
        return new FakeSource(key, true, () -> {
            throw new SourceFetchException(reason);
        });
    }

    @Test
    void failingSourceDoesNotAbortRun() {
        OfferCollectionService service = service(
                failing(SourceKey.REWE, "Zugriff gesperrt (HTTP 403)"),
                source(SourceKey.PENNY, Chain.PENNY, "Butter"),
                source(SourceKey.LIDL, Chain.LIDL, "Milch"));

        OfferCollectionService.CollectionResult result = service.collect();

        assertThat(result.offerCount()).isEqualTo(2);
        assertThat(result.failures()).containsEntry(SourceKey.REWE, "Zugriff gesperrt (HTTP 403)");
        assertThat(result.failures()).hasSize(1);
        verify(offers).deleteActiveFrom(TODAY);
        ArgumentCaptor<Iterable<GroceryOffer>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(offers).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(GroceryOffer::getTitle).containsExactlyInAnyOrder("Butter", "Milch");
        GrocerySourceRun rewe = savedRuns.stream().filter(r -> r.getSourceKey() == SourceKey.REWE).findFirst().orElseThrow();
        assertThat(rewe.isOk()).isFalse();
        assertThat(rewe.getReason()).isEqualTo("Zugriff gesperrt (HTTP 403)");
        GrocerySourceRun penny = savedRuns.stream().filter(r -> r.getSourceKey() == SourceKey.PENNY).findFirst().orElseThrow();
        assertThat(penny.isOk()).isTrue();
        assertThat(penny.getOfferCount()).isEqualTo(1);
    }

    @Test
    void runtimeExceptionInOneAdapterDoesNotAbortRun() {
        OfferSource buggy = new FakeSource(SourceKey.NORMA, true, () -> {
            throw new IllegalStateException("Parser kaputt");
        });

        OfferCollectionService.CollectionResult result = service(buggy, source(SourceKey.LIDL, Chain.LIDL, "Milch")).collect();

        assertThat(result.offerCount()).isEqualTo(1);
        assertThat(result.failures().get(SourceKey.NORMA)).contains("Parser kaputt");
    }

    @Test
    void disabledSourceIsSkipped() {
        properties.getSources().put(SourceKey.LIDL, false);
        OfferSource lidl = source(SourceKey.LIDL, Chain.LIDL, "Milch");
        OfferCollectionService.CollectionResult result = service(lidl, source(SourceKey.PENNY, Chain.PENNY, "Butter")).collect();

        assertThat(result.offerCount()).isEqualTo(1);
        assertThat(result.failures()).isEmpty();
        assertThat(((FakeSource) lidl).calls).isZero();
        assertThat(savedRuns).extracting(GrocerySourceRun::getSourceKey).doesNotContain(SourceKey.LIDL);
    }

    @Test
    void sourceReportingItselfDisabledIsSkipped() {
        FakeSource off = new FakeSource(SourceKey.LIDL, false, List::of);
        service(off, source(SourceKey.PENNY, Chain.PENNY, "Butter")).collect();

        assertThat(off.calls).isZero();
    }

    @Test
    void allSourcesFailingKeepsOldOffers() {
        OfferCollectionService.CollectionResult result = service(
                failing(SourceKey.REWE, "HTTP 500"), failing(SourceKey.PENNY, "HTTP 500")).collect();

        assertThat(result.offerCount()).isZero();
        assertThat(result.failures()).hasSize(2);
        verify(offers, never()).deleteActiveFrom(any());
        verify(offers, never()).saveAll(anyIterable());
    }

    @Test
    void allSourcesEmptyKeepsOldOffers() {
        service(new FakeSource(SourceKey.REWE, true, List::of)).collect();

        verify(offers, never()).deleteActiveFrom(any());
    }

    @Test
    void resolvesMarketIdFromChainAndExternalId() {
        GroceryMarket market = new GroceryMarket();
        market.setId(7L);
        market.setChain(Chain.REWE);
        market.setExternalId("240767");
        when(markets.findAll()).thenReturn(List.of(market));
        OfferSource rewe = new FakeSource(SourceKey.REWE, true, () -> List.of(RawOffer.builder().source(SourceKey.REWE)
                .sourceOfferId("r1").chain(Chain.REWE).marketExternalId("240767").title("Cola")
                .price(new BigDecimal("1.99")).validFrom(TODAY).validTo(TODAY.plusDays(5)).build()));

        service(rewe).collect();

        ArgumentCaptor<Iterable<GroceryOffer>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(offers).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(GroceryOffer::getMarketId).containsExactly(7L);
        assertThat(saved.getValue().iterator().next().getSources()).isEqualTo("REWE");
        assertThat(saved.getValue().iterator().next().getSourceOfferIds()).isEqualTo("REWE:r1");
    }

    private static final class FakeSource implements OfferSource {

        private final SourceKey key;
        private final boolean enabled;
        private final Fetcher fetcher;
        int calls;

        FakeSource(SourceKey key, boolean enabled, Fetcher fetcher) {
            this.key = key;
            this.enabled = enabled;
            this.fetcher = fetcher;
        }

        @Override
        public SourceKey key() {
            return key;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<RawOffer> fetch(FetchContext ctx) throws SourceFetchException {
            calls++;
            return fetcher.fetch();
        }
    }

    @FunctionalInterface
    private interface Fetcher {
        List<RawOffer> fetch() throws SourceFetchException;
    }
}
