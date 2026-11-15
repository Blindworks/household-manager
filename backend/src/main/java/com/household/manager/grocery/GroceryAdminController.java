package com.household.manager.grocery;

import com.household.manager.audit.AuditService;
import com.household.manager.grocery.dto.ChainEnabledDto;
import com.household.manager.grocery.dto.GrocerySettingsDto;
import com.household.manager.grocery.dto.MarketDto;
import com.household.manager.grocery.dto.MarketRefreshDto;
import com.household.manager.grocery.dto.MarketSelectionDto;
import com.household.manager.grocery.dto.SourceStatusDto;
import com.household.manager.grocery.entity.GrocerySourceRun;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GrocerySourceRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin-API der Supermarkt-Angebote (Einstellungen, Märkte, Quellenstatus). */
@RestController
@RequestMapping("/v1/admin/groceries")
@RequiredArgsConstructor
public class GroceryAdminController {

    private final GrocerySettingsService settings;
    private final GroceryMarketService marketService;
    private final GrocerySourceRunRepository sourceRuns;
    private final GroceryProperties properties;
    private final AuditService auditService;

    @GetMapping("/settings")
    public GrocerySettingsDto getSettings() {
        return new GrocerySettingsDto(settings.zip(), settings.lat(), settings.lng(),
                settings.dealThresholdPercent());
    }

    @PutMapping("/settings")
    public GrocerySettingsDto updateSettings(@RequestBody GrocerySettingsDto dto) {
        settings.update(dto);
        auditService.record("grocery.settings.update",
                "zip=" + dto.zip() + ", threshold=" + dto.dealThresholdPercent());
        return getSettings();
    }

    @GetMapping("/markets")
    public List<MarketDto> getMarkets() {
        return marketService.listMarkets();
    }

    @PostMapping("/markets/refresh")
    public MarketRefreshDto refreshMarkets() {
        return marketService.refresh();
    }

    @PutMapping("/markets/{id}")
    public MarketDto selectMarket(@PathVariable long id, @RequestBody MarketSelectionDto body) {
        MarketDto market = marketService.setSelected(id, body.selected());
        auditService.record("grocery.market.select",
                market.chain() + " " + market.name() + " -> " + body.selected());
        return market;
    }

    @PutMapping("/chains/{chain}")
    public ChainEnabledDto updateChain(@PathVariable Chain chain, @RequestBody ChainEnabledDto body) {
        marketService.setChainEnabled(chain, body.enabled());
        auditService.record("grocery.chain.update", chain + " -> " + body.enabled());
        return body;
    }

    @GetMapping("/sources")
    public List<SourceStatusDto> getSources() {
        Map<SourceKey, GrocerySourceRun> runs = sourceRuns.findAll().stream()
                .collect(Collectors.toMap(GrocerySourceRun::getSourceKey, Function.identity()));
        return Arrays.stream(SourceKey.values()).map(key -> {
            GrocerySourceRun run = runs.get(key);
            boolean enabled = properties.isSourceEnabled(key);
            if (run == null) {
                return new SourceStatusDto(key, enabled, null, null, null, "noch nicht gelaufen");
            }
            return new SourceStatusDto(key, enabled, run.getLastRunAt(), run.getOfferCount(),
                    run.isOk(), run.getReason());
        }).toList();
    }
}
