package com.household.manager.grocery.matching;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.ClaudeOfferJudge.JudgeResponse;
import com.household.manager.grocery.matching.MatchingService.MatchingResult;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchingServiceTest {

    private final ClaudeOfferJudge judge = mock(ClaudeOfferJudge.class);
    private final GroceryMatchRepository matchRepo = mock(GroceryMatchRepository.class);
    private final GroceryProductRepository productRepo = mock(GroceryProductRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private MatchingService service;

    @BeforeEach
    void setUp() {
        service = new MatchingService(new CandidatePrefilter(), judge, matchRepo, productRepo, clock);
        when(matchRepo.findByProductId(any())).thenReturn(List.of());
    }

    private GroceryProduct product(long id, String search, String exclude) {
        GroceryProduct p = new GroceryProduct();
        p.setId(id);
        p.setName("P" + id);
        p.setBaseUnit(BaseUnit.KILOGRAM);
        p.setSearchWords(search);
        p.setMustWords("");
        p.setExcludeWords(exclude);
        return p;
    }

    private GroceryOffer offer(String title, String fp) {
        GroceryOffer o = new GroceryOffer();
        o.setTitle(title);
        o.setPrice(BigDecimal.ONE);
        o.setFingerprint(fp);
        return o;
    }

    private GroceryMatch stored(long productId, String fp, GroceryMatch.Verdict v, GroceryMatch.DecidedBy by) {
        GroceryMatch m = new GroceryMatch();
        m.setProductId(productId);
        m.setFingerprint(fp);
        m.setVerdict(v);
        m.setDecidedBy(by);
        return m;
    }

    private Optional<JudgeResponse> judged(Judgement... js) {
        return Optional.of(new JudgeResponse(List.of(js), 10, 5));
    }

    @Test
    void cachedVerdictIsNotAskedAgain() {
        when(matchRepo.findByProductId(1L)).thenReturn(
                List.of(stored(1, "fp1", GroceryMatch.Verdict.MATCH, GroceryMatch.DecidedBy.CLAUDE)));

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")),
                List.of(offer("Butter 250g", "fp1")), 5);

        verify(judge, never()).judge(any(), anyList(), anyList());
        assertThat(result.matches().get(1L)).hasSize(1);
        assertThat(result.aiCallsUsed()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void newJudgementIsStoredAndReturned() {
        when(judge.judge(any(), anyList(), anyList()))
                .thenReturn(judged(new Judgement("fp1", Verdict.MAYBE, ReasonKind.SORT_UNCLEAR, "Sorten")));

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")),
                List.of(offer("Butter 250g", "fp1")), 5);

        ArgumentCaptor<List<GroceryMatch>> captor = ArgumentCaptor.forClass(List.class);
        verify(matchRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(m -> {
            assertThat(m.getDecidedBy()).isEqualTo(GroceryMatch.DecidedBy.CLAUDE);
            assertThat(m.getVerdict()).isEqualTo(GroceryMatch.Verdict.MAYBE);
            assertThat(m.getReasonKind()).isEqualTo("SORT_UNCLEAR");
        });
        assertThat(result.matches().get(1L)).singleElement().satisfies(m -> {
            assertThat(m.verdict()).isEqualTo(Verdict.MAYBE);
            assertThat(m.userDecided()).isFalse();
        });
        assertThat(result.inputTokens()).isEqualTo(10);
        assertThat(result.outputTokens()).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void userNoIsPermanent() {
        when(matchRepo.findByProductId(1L)).thenReturn(
                List.of(stored(1, "fpNo", GroceryMatch.Verdict.NO, GroceryMatch.DecidedBy.USER)));
        when(judge.judge(any(), anyList(), anyList()))
                .thenReturn(judged(new Judgement("fp2", Verdict.MATCH, null, null)));

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")),
                List.of(offer("Butter alt", "fpNo"), offer("Butter neu", "fp2")), 5);

        assertThat(result.matches().get(1L)).extracting(m -> m.offer().getFingerprint()).containsExactly("fp2");
        ArgumentCaptor<List<GroceryOffer>> block = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> rejected = ArgumentCaptor.forClass(List.class);
        verify(judge).judge(any(), block.capture(), rejected.capture());
        assertThat(block.getValue()).extracting(GroceryOffer::getFingerprint).containsExactly("fp2");
        assertThat(rejected.getValue()).containsExactly("Butter alt");
    }

    @Test
    void hardRuleOverridesClaudeMatch() {
        GroceryProduct p = product(1, "butter", "bio");
        when(judge.judge(any(), anyList(), anyList()))
                .thenReturn(judged(new Judgement("fp1", Verdict.MATCH, null, null)));
        CandidatePrefilter passThrough = new CandidatePrefilter() {
            @Override
            public List<GroceryOffer> candidates(GroceryProduct product, List<GroceryOffer> offers) {
                return offers;
            }
        };
        MatchingService s = new MatchingService(passThrough, judge, matchRepo, productRepo, clock);

        MatchingResult result = s.matchAll(List.of(p), List.of(offer("Butter Bio", "fp1")), 5);

        assertThat(result.matches().get(1L)).isEmpty();
    }

    @Test
    void budgetDefersRemainingProducts() {
        when(judge.judge(any(), anyList(), anyList())).thenReturn(Optional.empty());

        MatchingResult result = service.matchAll(
                List.of(product(1, "butter", ""), product(2, "milch", "")),
                List.of(offer("Butter", "fp1"), offer("Milch", "fp2")), 1);

        assertThat(result.aiCallsUsed()).isEqualTo(1);
        assertThat(result.productsDeferred()).isEqualTo(1);
        verify(matchRepo, never()).saveAll(any());
        verify(matchRepo, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blocksOfFortyEachCostOneCallAndDuplicatesAskedOnce() {
        List<GroceryOffer> offers = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            offers.add(offer("Butter " + i, "fp" + i));
        }
        offers.add(offer("Butter dup", "fp0"));
        when(judge.judge(any(), anyList(), anyList())).thenReturn(Optional.empty());

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")), offers, 5);

        assertThat(result.aiCallsUsed()).isEqualTo(2);
        assertThat(result.aiFailures()).isEqualTo(2);
        assertThat(result.productsDeferred()).isZero();
        ArgumentCaptor<List<GroceryOffer>> block = ArgumentCaptor.forClass(List.class);
        verify(judge, times(2)).judge(any(), block.capture(), anyList());
        assertThat(block.getAllValues().get(0)).hasSize(40);
        assertThat(block.getAllValues().get(1)).hasSize(5);
    }

    @Test
    void conservativeVerdictWinsForDuplicateJudgements() {
        when(judge.judge(any(), anyList(), anyList())).thenReturn(judged(
                new Judgement("fp1", Verdict.MATCH, null, null),
                new Judgement("fp1", Verdict.NO, null, "x")));

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")),
                List.of(offer("Butter", "fp1")), 5);

        assertThat(result.matches().get(1L)).isEmpty();
        assertThat(result.aiFailures()).isZero();
    }

    @Test
    void unjudgedCandidatesAreNotMatches() {
        when(judge.judge(any(), anyList(), anyList())).thenReturn(Optional.empty());

        MatchingResult result = service.matchAll(List.of(product(1, "butter", "")),
                List.of(offer("Butter", "fp1")), 5);

        assertThat(result.matches().get(1L)).isEmpty();
        verify(matchRepo, never()).saveAll(any());
    }

    @Test
    void repositoryFailureDefersOnlyThatProduct() {
        when(matchRepo.findByProductId(1L)).thenThrow(new IllegalStateException("db"));
        when(judge.judge(any(), anyList(), anyList()))
                .thenReturn(judged(new Judgement("fp2", Verdict.MATCH, null, null)));

        MatchingResult result = service.matchAll(
                List.of(product(1, "butter", ""), product(2, "milch", "")),
                List.of(offer("Butter", "fp1"), offer("Milch", "fp2")), 5);

        assertThat(result.productsDeferred()).isEqualTo(1);
        assertThat(result.matches().get(2L)).hasSize(1);
    }

    @Test
    void userConfirmKeepsPackagingReason() {
        GroceryMatch m = stored(1, "fp1", GroceryMatch.Verdict.MAYBE, GroceryMatch.DecidedBy.CLAUDE);
        m.setReasonKind("PACKAGING_DIFFERS");
        m.setReason("Dose statt Glas");
        when(productRepo.existsById(1L)).thenReturn(true);
        when(matchRepo.findByProductIdAndFingerprint(1L, "fp1")).thenReturn(Optional.of(m));

        service.recordUserVerdict(1, "fp1", true);

        assertThat(m.getVerdict()).isEqualTo(GroceryMatch.Verdict.MATCH);
        assertThat(m.getDecidedBy()).isEqualTo(GroceryMatch.DecidedBy.USER);
        assertThat(m.getReasonKind()).isEqualTo("PACKAGING_DIFFERS");
        assertThat(m.getReason()).isEqualTo("Dose statt Glas");
        verify(matchRepo).save(m);
    }

    @Test
    void userRejectSetsNoAndClearsReasonKind() {
        GroceryMatch m = stored(1, "fp1", GroceryMatch.Verdict.MATCH, GroceryMatch.DecidedBy.CLAUDE);
        m.setReasonKind("OTHER");
        when(productRepo.existsById(1L)).thenReturn(true);
        when(matchRepo.findByProductIdAndFingerprint(1L, "fp1")).thenReturn(Optional.of(m));

        service.recordUserVerdict(1, "fp1", false);

        assertThat(m.getVerdict()).isEqualTo(GroceryMatch.Verdict.NO);
        assertThat(m.getReasonKind()).isNull();
        assertThat(m.getReason()).isEqualTo("vom Nutzer abgelehnt");
        assertThat(m.getDecidedBy()).isEqualTo(GroceryMatch.DecidedBy.USER);
    }

    @Test
    void confirmOnlyAllowedForMaybe() {
        GroceryMatch m = stored(1, "fp1", GroceryMatch.Verdict.MATCH, GroceryMatch.DecidedBy.CLAUDE);
        when(productRepo.existsById(1L)).thenReturn(true);
        when(matchRepo.findByProductIdAndFingerprint(1L, "fp1")).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> service.recordUserVerdict(1, "fp1", true))
                .isInstanceOf(IllegalStateException.class);
        verify(matchRepo, never()).save(any());
    }

    @Test
    void unknownProductIsNotFound() {
        when(productRepo.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.recordUserVerdict(9, "fp1", false))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
