package com.household.manager.grocery;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.dto.MarketDto;
import com.household.manager.grocery.dto.MarketRefreshDto;
import com.household.manager.grocery.entity.GroceryMarket;
import com.household.manager.grocery.source.FetchContext;
import com.household.manager.grocery.source.MarketCandidate;
import com.household.manager.grocery.source.MarketSearch;
import com.household.manager.repository.GroceryMarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Marktkandidaten, deren Auswahl und die daraus abgeleiteten Abrufeingaben. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroceryMarketService implements FetchContextProvider {

    private final GroceryMarketRepository markets;
    private final List<MarketSearch> searches;
    private final GrocerySettingsService settings;

    /** Sucht bei jeder Kette neu; die Auswahl bleibt, nicht mehr gefundene Märkte bleiben stehen. */
    public List<MarketDto> refreshCandidates() {
        return refresh().markets();
    }

    /** Bewusst ohne umschließende Transaktion: Netzabrufe halten keine DB-Transaktion, jedes save() ist eigen. */
    public MarketRefreshDto refresh() {
        String zip = settings.zip();
        double lat = settings.lat();
        double lng = settings.lng();
        List<Chain> failed = new ArrayList<>();
        for (MarketSearch search : searches) {
            try {
                for (MarketCandidate candidate : search.search(zip, lat, lng)) {
                    upsert(candidate);
                }
            } catch (Exception e) {
                log.warn("Marktsuche {} fehlgeschlagen: {}", search.chain(), e.getMessage());
                failed.add(search.chain());
            }
        }
        return new MarketRefreshDto(listMarkets(), failed);
    }

    @Transactional(readOnly = true)
    public List<MarketDto> listMarkets() {
        return markets.findAll().stream()
                .sorted(Comparator.comparing(GroceryMarket::getChain)
                        .thenComparing(GroceryMarket::getName, String.CASE_INSENSITIVE_ORDER))
                .map(GroceryMarketService::toDto)
                .toList();
    }

    @Transactional
    public MarketDto setSelected(long id, boolean selected) {
        GroceryMarket market = markets.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Markt " + id + " nicht gefunden"));
        market.setSelected(selected);
        return toDto(markets.save(market));
    }

    @Transactional(readOnly = true)
    public List<MarketCandidate> selectedMarkets() {
        return markets.findBySelectedTrue().stream()
                .map(m -> new MarketCandidate(m.getChain(), m.getExternalId(), m.getName(),
                        m.getAddress(), m.getLat(), m.getLng(), m.getRegion()))
                .toList();
    }

    /** Ketten mit Märkten: aktiv, sobald ein Markt gewählt ist; die übrigen per Kettenschalter. */
    @Transactional(readOnly = true)
    public Set<Chain> enabledChains() {
        Set<Chain> withSelectedMarket = EnumSet.noneOf(Chain.class);
        markets.findBySelectedTrue().forEach(m -> withSelectedMarket.add(m.getChain()));
        Set<Chain> enabled = EnumSet.noneOf(Chain.class);
        for (Chain chain : Chain.values()) {
            boolean on = chain.hasMarkets() ? withSelectedMarket.contains(chain) : settings.chainEnabled(chain);
            if (on) {
                enabled.add(chain);
            }
        }
        return enabled;
    }

    public void setChainEnabled(Chain chain, boolean enabled) {
        if (chain.hasMarkets()) {
            throw new IllegalArgumentException(
                    chain.displayName() + " wird über die Marktauswahl gesteuert, nicht per Schalter.");
        }
        settings.setChainEnabled(chain, enabled);
    }

    @Override
    public FetchContext current() {
        return new FetchContext(settings.zip(), settings.lat(), settings.lng(),
                selectedMarkets(), enabledChains());
    }

    private void upsert(MarketCandidate candidate) {
        GroceryMarket market = markets.findByChainAndExternalId(candidate.chain(), candidate.externalId())
                .orElseGet(GroceryMarket::new);
        market.setChain(candidate.chain());
        market.setExternalId(candidate.externalId());
        market.setName(candidate.name());
        market.setAddress(candidate.address());
        market.setLat(candidate.lat());
        market.setLng(candidate.lng());
        market.setRegion(candidate.region());
        markets.save(market);
    }

    private static MarketDto toDto(GroceryMarket m) {
        return new MarketDto(m.getId(), m.getChain(), m.getExternalId(), m.getName(), m.getAddress(),
                m.getLat(), m.getLng(), m.isSelected());
    }
}
