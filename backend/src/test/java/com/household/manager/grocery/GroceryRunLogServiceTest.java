package com.household.manager.grocery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.GroceryRunLogService.RunOutcome;
import com.household.manager.grocery.OfferCollectionService.CollectionResult;
import com.household.manager.grocery.dto.RunLogDetailDto;
import com.household.manager.grocery.dto.RunProductLine;
import com.household.manager.grocery.dto.RunSourceLine;
import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.entity.GroceryRunLog;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.MatchingService.MatchingResult;
import com.household.manager.grocery.matching.MatchingService.ProductStats;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryProductRepository;
import com.household.manager.repository.GroceryRunLogRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroceryRunLogServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 4, 0, 0, 700_000_000);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 20, 4, 6, 30, 200_000_000);

    private final GroceryRunLogRepository logs = mock(GroceryRunLogRepository.class);
    private final GroceryMatchRepository matches = mock(GroceryMatchRepository.class);
    private final GroceryOfferRepository offers = mock(GroceryOfferRepository.class);
    private final GroceryProductRepository products = mock(GroceryProductRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GroceryRunLogService service =
            new GroceryRunLogService(logs, matches, offers, products, objectMapper);

    private static GroceryProduct product(long id, String name) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setName(name);
        p.setBaseUnit(BaseUnit.LITER);
        p.setEstimatedPricePerUnit(new BigDecimal("1.99"));
        return p;
    }

    private static GroceryOffer offer(long id, String title, String fingerprint) {
        GroceryOffer o = new GroceryOffer();
        o.setId(id);
        o.setChain(Chain.PENNY);
        o.setTitle(title);
        o.setPrice(new BigDecimal("0.86"));
        o.setBasePrice(new BigDecimal("1.43"));
        o.setBaseUnit(BaseUnit.LITER);
        o.setFingerprint(fingerprint);
        return o;
    }

    private RunOutcome outcome() {
        GroceryProduct cola = product(1L, "Cola Zero");
        MatchedOffer sure = new MatchedOffer(offer(10L, "Cola Zero 1,5 l", "fpA"), Verdict.MATCH, null, null, false);
        MatchedOffer maybe = new MatchedOffer(offer(11L, "Cola Mix", "fpB"), Verdict.MAYBE, null, null, false);
        Deal deal = new Deal(sure, new BigDecimal("0.57"), new BigDecimal("1.99"), Deal.ReferenceKind.USUAL, 71,
                false, false, null);
        MatchingResult matching = new MatchingResult(Map.of(1L, List.of(sure, maybe)), 1, 0, 1200, 300, 0,
                Map.of(1L, new ProductStats(5, 2, 2, 1, 0, false, null)));
        CollectionResult collection = new CollectionResult(120, Map.of(SourceKey.REWE, "Zugriff gesperrt"),
                Map.of(SourceKey.PENNY, 80, SourceKey.MARKTGURU, 60), 140);
        return new RunOutcome(collection, matching, Map.of(1L, List.of(deal)), List.of(cola), 1);
    }

    @Test
    void buildStoresCountsAndDetailsAndTruncatesSeconds() throws Exception {
        GroceryRunLog row = service.build(GroceryRunLog.Trigger.NIGHTLY, START, END, outcome(), null);

        assertThat(row.isOk()).isTrue();
        assertThat(row.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 4, 0, 0));
        assertThat(row.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 4, 6, 30));
        assertThat(row.getOffers()).isEqualTo(120);
        assertThat(row.getRawOffers()).isEqualTo(140);
        assertThat(row.getProducts()).isEqualTo(1);
        assertThat(row.getAiCalls()).isEqualTo(1);
        assertThat(row.getEstimateCalls()).isEqualTo(1);
        assertThat(row.getInputTokens()).isEqualTo(1200);
        assertThat(row.getNewJudgements()).isEqualTo(2);
        assertThat(row.getDeals()).isEqualTo(1);
        assertThat(row.getSourceFailures()).isEqualTo(1);

        GroceryRunLogService.Details details =
                objectMapper.readValue(row.getDetails(), GroceryRunLogService.Details.class);
        assertThat(details.sources()).containsExactly(
                new RunSourceLine("MARKTGURU", true, 60, null),
                new RunSourceLine("REWE", false, null, "Zugriff gesperrt"),
                new RunSourceLine("PENNY", true, 80, null));
        assertThat(details.products()).containsExactly(new RunProductLine(1L, "Cola Zero", 5, 2, 2, 1, 0, false,
                null, 1, 1, 1, null, new BigDecimal("1.99"), "LITER"));
    }

    @Test
    void failedRunHasErrorAndNoDetails() {
        GroceryRunLog row = service.build(GroceryRunLog.Trigger.MANUAL, START, END, null, "x".repeat(600));

        assertThat(row.isOk()).isFalse();
        assertThat(row.getError()).hasSize(500);
        assertThat(row.getDetails()).isNull();
        assertThat(row.getOffers()).isZero();
    }

    @Test
    void recordNeverThrows() {
        when(logs.save(any())).thenThrow(new RuntimeException("DB weg"));

        assertThatCode(() -> service.record(GroceryRunLog.Trigger.NIGHTLY, START, END, outcome(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void detailListsClaudeJudgementsOfTheRunWindowWithOfferData() {
        GroceryRunLog row = service.build(GroceryRunLog.Trigger.NIGHTLY, START, END, outcome(), null);
        row.setId(7L);
        when(logs.findById(7L)).thenReturn(Optional.of(row));
        GroceryMatch known = match(1L, "fpA", GroceryMatch.Verdict.MATCH, null);
        GroceryMatch gone = match(2L, "fpGone", GroceryMatch.Verdict.NO, "Sahne statt Butter");
        when(matches.findByDecidedByAndDecidedAtBetweenOrderByProductIdAscIdAsc(GroceryMatch.DecidedBy.CLAUDE,
                LocalDateTime.of(2026, 11, 20, 4, 0, 0), LocalDateTime.of(2026, 11, 20, 4, 6, 31)))
                .thenReturn(List.of(known, gone));
        when(offers.findByFingerprintIn(anyCollection())).thenReturn(List.of(offer(10L, "Cola Zero 1,5 l", "fpA")));
        when(products.findAllById(any())).thenReturn(List.of(product(2L, "Butter")));

        RunLogDetailDto detail = service.detail(7L);

        assertThat(detail.run().id()).isEqualTo(7L);
        assertThat(detail.sources()).hasSize(3);
        assertThat(detail.products()).singleElement().extracting(RunProductLine::name).isEqualTo("Cola Zero");
        assertThat(detail.judgements()).hasSize(2);
        assertThat(detail.judgements().get(0).productName()).isEqualTo("Cola Zero");
        assertThat(detail.judgements().get(0).offerTitle()).isEqualTo("Cola Zero 1,5 l");
        assertThat(detail.judgements().get(0).chain()).isEqualTo("Penny");
        assertThat(detail.judgements().get(1).productName()).isEqualTo("Butter");
        assertThat(detail.judgements().get(1).offerTitle()).isNull();
        assertThat(detail.judgements().get(1).reason()).isEqualTo("Sahne statt Butter");
        assertThat(detail.judgementsTruncated()).isFalse();
    }

    @Test
    void unknownRunIs404() {
        when(logs.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listClampsTheLimit() {
        when(logs.findAllByOrderByStartedAtDescIdDesc(any())).thenReturn(List.of());

        service.list(1000);

        verify(logs).findAllByOrderByStartedAtDescIdDesc(org.springframework.data.domain.PageRequest.of(0, 100));
    }

    private static GroceryMatch match(long productId, String fingerprint, GroceryMatch.Verdict verdict,
                                      String reason) {
        GroceryMatch m = new GroceryMatch();
        m.setProductId(productId);
        m.setFingerprint(fingerprint);
        m.setVerdict(verdict);
        m.setReason(reason);
        m.setDecidedBy(GroceryMatch.DecidedBy.CLAUDE);
        return m;
    }
}
