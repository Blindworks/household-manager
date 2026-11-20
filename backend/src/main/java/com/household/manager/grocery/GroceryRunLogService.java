package com.household.manager.grocery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.OfferCollectionService.CollectionResult;
import com.household.manager.grocery.dto.RunJudgementLine;
import com.household.manager.grocery.dto.RunLogDetailDto;
import com.household.manager.grocery.dto.RunLogSummaryDto;
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
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.grocery.source.SourceKey;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryProductRepository;
import com.household.manager.repository.GroceryRunLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protokoll der Supermarkt-Läufe für die Admin-Seite: je Lauf Kennzahlen, Quellen und Produkte; die Claude-Urteile
 * eines Laufs werden beim Lesen aus {@code grocery_match} geholt (Zeitfenster des Laufs), nicht doppelt gespeichert.
 * Schreiben wirft nie — das Protokoll darf keinen Lauf kippen.
 */
@Slf4j
@Service
public class GroceryRunLogService {

    static final int MAX_LIST = 100;
    static final int MAX_JUDGEMENTS = 500;
    static final int ERROR_LENGTH = 500;

    /** Was ein erfolgreicher Lauf zum Protokoll beiträgt. */
    public record RunOutcome(CollectionResult collection, MatchingResult matching, Map<Long, List<Deal>> deals,
                             List<GroceryProduct> products, int estimateCalls) {
    }

    /** Inhalt der Spalte {@code details}. */
    public record Details(List<RunSourceLine> sources, List<RunProductLine> products) {
    }

    private final GroceryRunLogRepository logs;
    private final GroceryMatchRepository matches;
    private final GroceryOfferRepository offers;
    private final GroceryProductRepository products;
    private final ObjectMapper objectMapper;

    public GroceryRunLogService(GroceryRunLogRepository logs, GroceryMatchRepository matches,
                                GroceryOfferRepository offers, GroceryProductRepository products,
                                ObjectMapper objectMapper) {
        this.logs = logs;
        this.matches = matches;
        this.offers = offers;
        this.products = products;
        this.objectMapper = objectMapper;
    }

    /** Speichert einen Lauf; {@code outcome} fehlt bei einem abgebrochenen Lauf. Wirft nie. */
    public void record(GroceryRunLog.Trigger trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
                       RunOutcome outcome, String error) {
        try {
            logs.save(build(trigger, startedAt, finishedAt, outcome, error));
        } catch (RuntimeException e) {
            log.warn("Supermarkt-Lauf konnte nicht protokolliert werden: {}", e.getMessage());
        }
    }

    GroceryRunLog build(GroceryRunLog.Trigger trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
                        RunOutcome outcome, String error) {
        GroceryRunLog row = new GroceryRunLog();
        row.setTrigger(trigger);
        // DATETIME hat keine Bruchsekunden; abgeschnitten, damit das Urteilsfenster nicht durch Runden verrutscht
        row.setStartedAt(startedAt.truncatedTo(ChronoUnit.SECONDS));
        row.setFinishedAt(finishedAt.truncatedTo(ChronoUnit.SECONDS));
        row.setOk(error == null);
        row.setError(error == null || error.length() <= ERROR_LENGTH ? error : error.substring(0, ERROR_LENGTH));
        if (outcome == null) {
            return row;
        }
        MatchingResult matching = outcome.matching();
        CollectionResult collection = outcome.collection();
        row.setOffers(collection.offerCount());
        row.setRawOffers(collection.rawCount());
        row.setProducts(outcome.products().size());
        row.setAiCalls(matching.aiCallsUsed());
        row.setEstimateCalls(outcome.estimateCalls());
        row.setInputTokens(matching.inputTokens());
        row.setOutputTokens(matching.outputTokens());
        row.setAiFailures(matching.aiFailures());
        row.setDeferred(matching.productsDeferred());
        row.setNewJudgements(matching.stats().values().stream().mapToInt(ProductStats::newJudgements).sum());
        row.setDeals(outcome.deals().values().stream().mapToInt(List::size).sum());
        row.setSourceFailures(collection.failures().size());
        try {
            row.setDetails(objectMapper.writeValueAsString(
                    new Details(sourceLines(collection), productLines(outcome))));
        } catch (JsonProcessingException e) {
            log.warn("Details des Supermarkt-Laufs nicht serialisierbar: {}", e.getMessage());
        }
        return row;
    }

    static List<RunSourceLine> sourceLines(CollectionResult collection) {
        List<RunSourceLine> lines = new ArrayList<>();
        for (SourceKey key : SourceKey.values()) {
            Integer delivered = collection.delivered().get(key);
            String failure = collection.failures().get(key);
            if (failure != null) {
                lines.add(new RunSourceLine(key.name(), false, null, failure));
            } else if (delivered != null) {
                lines.add(new RunSourceLine(key.name(), true, delivered, null));
            }
        }
        return lines;
    }

    static List<RunProductLine> productLines(RunOutcome outcome) {
        List<RunProductLine> lines = new ArrayList<>();
        for (GroceryProduct product : outcome.products()) {
            ProductStats stats = outcome.matching().stats().getOrDefault(product.getId(),
                    new ProductStats(0, 0, 0, 0, 0, false, null));
            List<MatchedOffer> matched = outcome.matching().matches().getOrDefault(product.getId(), List.of());
            int sure = (int) matched.stream().filter(m -> m.verdict() == Verdict.MATCH).count();
            lines.add(new RunProductLine(product.getId(), product.getName(), stats.candidates(), stats.unjudged(),
                    stats.newJudgements(), stats.aiCalls(), stats.aiFailures(), stats.deferred(), stats.error(), sure,
                    matched.size() - sure, outcome.deals().getOrDefault(product.getId(), List.of()).size(),
                    product.getNormalPricePerUnit(), product.getEstimatedPricePerUnit(),
                    product.getBaseUnit() == null ? null : product.getBaseUnit().name()));
        }
        return lines;
    }

    public List<RunLogSummaryDto> list(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIST));
        return logs.findAllByOrderByStartedAtDescIdDesc(PageRequest.of(0, size)).stream()
                .map(RunLogSummaryDto::of).toList();
    }

    public RunLogDetailDto detail(long id) {
        GroceryRunLog row = logs.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lauf", "id", id));
        Details details = readDetails(row);
        List<GroceryMatch> decided = matches.findByDecidedByAndDecidedAtBetweenOrderByProductIdAscIdAsc(
                GroceryMatch.DecidedBy.CLAUDE, row.getStartedAt(), row.getFinishedAt().plusSeconds(1));
        boolean truncated = decided.size() > MAX_JUDGEMENTS;
        List<GroceryMatch> shown = truncated ? decided.subList(0, MAX_JUDGEMENTS) : decided;
        return new RunLogDetailDto(RunLogSummaryDto.of(row), details.sources(), details.products(),
                judgementLines(shown, details.products()), truncated);
    }

    private Details readDetails(GroceryRunLog row) {
        if (row.getDetails() == null) {
            return new Details(List.of(), List.of());
        }
        try {
            Details details = objectMapper.readValue(row.getDetails(), Details.class);
            return new Details(details.sources() == null ? List.of() : details.sources(),
                    details.products() == null ? List.of() : details.products());
        } catch (JsonProcessingException e) {
            log.warn("Details des Supermarkt-Laufs {} unlesbar: {}", row.getId(), e.getMessage());
            return new Details(List.of(), List.of());
        }
    }

    private List<RunJudgementLine> judgementLines(List<GroceryMatch> decided, List<RunProductLine> productLines) {
        if (decided.isEmpty()) {
            return List.of();
        }
        Set<String> fingerprints = decided.stream().map(GroceryMatch::getFingerprint).collect(Collectors.toSet());
        Map<String, GroceryOffer> offerByFingerprint = new HashMap<>();
        offers.findByFingerprintIn(fingerprints).stream()
                .sorted(Comparator.comparing(GroceryOffer::getId))
                .forEach(offer -> offerByFingerprint.putIfAbsent(offer.getFingerprint(), offer));
        Map<Long, String> names = new LinkedHashMap<>();
        productLines.forEach(line -> names.put(line.productId(), line.name()));
        Set<Long> missing = decided.stream().map(GroceryMatch::getProductId)
                .filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            products.findAllById(missing).forEach(p -> names.put(p.getId(), p.getName()));
        }
        List<RunJudgementLine> lines = new ArrayList<>();
        for (GroceryMatch match : decided) {
            GroceryOffer offer = offerByFingerprint.get(match.getFingerprint());
            lines.add(new RunJudgementLine(match.getProductId(),
                    names.getOrDefault(match.getProductId(), "Produkt " + match.getProductId()),
                    match.getVerdict().name(), match.getReasonKind(), match.getReason(),
                    offer == null ? null : offer.getTitle(),
                    offer == null || offer.getChain() == null ? null : offer.getChain().displayName(),
                    offer == null ? null : offer.getPrice(),
                    offer == null ? null : offer.getBasePrice(),
                    offer == null || offer.getBaseUnit() == null ? null : offer.getBaseUnit().name()));
        }
        return lines;
    }

    /** Retention: Läufe vor {@code cutoff}; Rückgabe = gelöschte Zeilen. */
    public int deleteStartedBefore(LocalDateTime cutoff) {
        return logs.deleteStartedBefore(cutoff);
    }
}
