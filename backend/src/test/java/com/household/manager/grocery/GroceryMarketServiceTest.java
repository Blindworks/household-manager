package com.household.manager.grocery;

import com.household.manager.grocery.dto.MarketRefreshDto;
import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.source.MarketCandidate;
import com.household.manager.grocery.source.MarketSearch;
import com.household.manager.grocery.source.SourceFetchException;
import com.household.manager.repository.GroceryMarketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroceryMarketServiceTest {

    private final List<GroceryMarket> db = new ArrayList<>();
    private GrocerySettingsService settings;
    private MarketSearch rewe;
    private MarketSearch penny;
    private GroceryMarketService service;

    @BeforeEach
    void setUp() {
        GroceryMarketRepository repo = mock(GroceryMarketRepository.class);
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(db));
        when(repo.findBySelectedTrue()).thenAnswer(i -> db.stream().filter(GroceryMarket::isSelected).toList());
        when(repo.findById(any())).thenAnswer(i -> db.stream()
                .filter(m -> m.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findByChainAndExternalId(any(), any())).thenAnswer(i -> db.stream()
                .filter(m -> m.getChain() == i.getArgument(0) && m.getExternalId().equals(i.getArgument(1)))
                .findFirst());
        when(repo.save(any())).thenAnswer(i -> {
            GroceryMarket m = i.getArgument(0);
            if (m.getId() == null) {
                m.setId((long) db.size() + 1);
                db.add(m);
            }
            return m;
        });
        settings = mock(GrocerySettingsService.class);
        when(settings.zip()).thenReturn("61118");
        when(settings.chainEnabled(any())).thenReturn(true);
        rewe = mock(MarketSearch.class);
        when(rewe.chain()).thenReturn(Chain.REWE);
        penny = mock(MarketSearch.class);
        when(penny.chain()).thenReturn(Chain.PENNY);
        service = new GroceryMarketService(repo, List.of(rewe, penny), settings);
    }

    private static MarketCandidate candidate(Chain chain, String id, String name) {
        return new MarketCandidate(chain, id, name, "Straße 1", 50.0, 8.0, null);
    }

    @Test
    void refreshKeepsSelection() throws Exception {
        when(rewe.search(any(), anyDouble(), anyDouble()))
                .thenReturn(List.of(candidate(Chain.REWE, "1", "REWE Alt")));
        service.refreshCandidates();
        service.setSelected(db.get(0).getId(), true);

        when(rewe.search(any(), anyDouble(), anyDouble()))
                .thenReturn(List.of(candidate(Chain.REWE, "1", "REWE Neu"), candidate(Chain.REWE, "2", "REWE Zwei")));
        service.refreshCandidates();

        assertThat(db).hasSize(2);
        assertThat(db.get(0).getName()).isEqualTo("REWE Neu");
        assertThat(db.get(0).isSelected()).isTrue();
        assertThat(db.get(1).isSelected()).isFalse();
    }

    @Test
    void refreshKeepsCandidatesNoLongerReturned() throws Exception {
        when(rewe.search(any(), anyDouble(), anyDouble()))
                .thenReturn(List.of(candidate(Chain.REWE, "1", "A")));
        service.refreshCandidates();
        when(rewe.search(any(), anyDouble(), anyDouble())).thenReturn(List.of());
        service.refreshCandidates();
        assertThat(db).hasSize(1);
    }

    @Test
    void failingChainDoesNotAbortRefresh() throws Exception {
        when(rewe.search(any(), anyDouble(), anyDouble())).thenThrow(new SourceFetchException("down"));
        when(penny.search(any(), anyDouble(), anyDouble()))
                .thenReturn(List.of(candidate(Chain.PENNY, "9", "Penny")));

        MarketRefreshDto result = service.refresh();

        assertThat(result.failedChains()).containsExactly(Chain.REWE);
        assertThat(result.markets()).hasSize(1);
    }

    @Test
    void chainWithMarketsIsEnabledOnlyWithSelectedMarket() throws Exception {
        when(rewe.search(any(), anyDouble(), anyDouble()))
                .thenReturn(List.of(candidate(Chain.REWE, "1", "REWE")));
        service.refreshCandidates();
        assertThat(service.enabledChains()).doesNotContain(Chain.REWE, Chain.PENNY);
        assertThat(service.enabledChains()).contains(Chain.LIDL, Chain.NORMA);

        service.setSelected(db.get(0).getId(), true);
        assertThat(service.enabledChains()).contains(Chain.REWE);
        assertThat(service.current().selectedMarkets()).hasSize(1);
    }

    @Test
    void chainWithoutMarketsFollowsSetting() {
        when(settings.chainEnabled(Chain.LIDL)).thenReturn(false);
        assertThat(service.enabledChains()).doesNotContain(Chain.LIDL).contains(Chain.NORMA);
    }

    @Test
    void chainSwitchIsRejectedForChainsWithMarkets() {
        assertThatThrownBy(() -> service.setChainEnabled(Chain.REWE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
