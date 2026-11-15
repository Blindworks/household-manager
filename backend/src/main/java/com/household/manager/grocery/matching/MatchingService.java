package com.household.manager.grocery.matching;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.grocery.entity.GroceryMatch.DecidedBy;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordnet Angebote den Produkten zu: Vorfilter, gespeicherte Urteile, Claude (im Budget) und Nutzerurteile. */
@Slf4j
@Service
public class MatchingService {

    public record MatchedOffer(GroceryOffer offer, Verdict verdict, ReasonKind reasonKind, String reason,
                               boolean userDecided) {
    }

    public record MatchingResult(Map<Long, List<MatchedOffer>> matches, int aiCallsUsed, int productsDeferred,
                                 int inputTokens, int outputTokens) {
    }

    static final String USER_REJECTED_REASON = "vom Nutzer abgelehnt";
    private static final int MAX_REJECTED_EXAMPLES = 10;

    private final CandidatePrefilter prefilter;
    private final ClaudeOfferJudge judge;
    private final GroceryMatchRepository matches;
    private final GroceryProductRepository products;
    private final Clock clock;

    public MatchingService(CandidatePrefilter prefilter, ClaudeOfferJudge judge, GroceryMatchRepository matches,
                           GroceryProductRepository products, Clock clock) {
        this.prefilter = prefilter;
        this.judge = judge;
        this.matches = matches;
        this.products = products;
        this.clock = clock;
    }

    /** Zähler eines Laufs. */
    private static final class Run {
        int budget;
        int calls;
        int inputTokens;
        int outputTokens;
    }

    private record ProductOutcome(List<MatchedOffer> matched, boolean deferred) {
    }

    public MatchingResult matchAll(List<GroceryProduct> productList, List<GroceryOffer> offers, int aiCallBudget) {
        Run run = new Run();
        run.budget = aiCallBudget;
        Map<Long, List<MatchedOffer>> result = new LinkedHashMap<>();
        int deferred = 0;
        for (GroceryProduct product : productList) {
            try {
                ProductOutcome outcome = matchProduct(product, offers, run);
                result.put(product.getId(), outcome.matched());
                if (outcome.deferred()) {
                    deferred++;
                }
            } catch (RuntimeException ex) {
                log.warn("Zuordnung für Produkt {} fehlgeschlagen: {}", product.getId(), ex.getMessage());
                result.putIfAbsent(product.getId(), List.of());
                deferred++;
            }
        }
        return new MatchingResult(result, run.calls, deferred, run.inputTokens, run.outputTokens);
    }

    private ProductOutcome matchProduct(GroceryProduct product, List<GroceryOffer> offers, Run run) {
        List<GroceryOffer> candidates = prefilter.candidates(product, offers);
        Map<String, GroceryMatch> stored = new HashMap<>();
        for (GroceryMatch match : matches.findByProductId(product.getId())) {
            stored.put(match.getFingerprint(), match);
        }

        Map<String, GroceryOffer> unknown = new LinkedHashMap<>();
        for (GroceryOffer candidate : candidates) {
            if (!stored.containsKey(candidate.getFingerprint())) {
                unknown.putIfAbsent(candidate.getFingerprint(), candidate);
            }
        }

        boolean deferred = false;
        if (!unknown.isEmpty()) {
            List<String> rejected = rejectedExamples(stored, offers);
            List<GroceryOffer> pending = new ArrayList<>(unknown.values());
            for (int from = 0; from < pending.size(); from += ClaudeOfferJudge.MAX_CANDIDATES) {
                if (run.calls >= run.budget) {
                    deferred = true;
                    break;
                }
                List<GroceryOffer> block = new ArrayList<>(pending.subList(from,
                        Math.min(from + ClaudeOfferJudge.MAX_CANDIDATES, pending.size())));
                run.calls++;
                judge.judge(product, block, rejected).ifPresent(response -> {
                    run.inputTokens += response.inputTokens();
                    run.outputTokens += response.outputTokens();
                    storeJudgements(product, block, response.judgements(), stored);
                });
            }
        }
        return new ProductOutcome(collect(product, candidates, stored), deferred);
    }

    /** Texte aktuell vorhandener Angebote, die der Nutzer für dieses Produkt abgelehnt hat. */
    private List<String> rejectedExamples(Map<String, GroceryMatch> stored, List<GroceryOffer> offers) {
        List<String> texts = new ArrayList<>();
        for (GroceryOffer offer : offers) {
            GroceryMatch match = stored.get(offer.getFingerprint());
            if (match != null && match.getDecidedBy() == DecidedBy.USER
                    && match.getVerdict() == GroceryMatch.Verdict.NO) {
                texts.add(CandidatePrefilter.offerText(offer));
                if (texts.size() == MAX_REJECTED_EXAMPLES) {
                    break;
                }
            }
        }
        return texts;
    }

    private void storeJudgements(GroceryProduct product, List<GroceryOffer> block, List<Judgement> judgements,
                                 Map<String, GroceryMatch> stored) {
        Map<String, Judgement> perFingerprint = new HashMap<>();
        for (Judgement judgement : judgements) {
            if (judgement.fingerprint() == null || judgement.verdict() == null) {
                continue;
            }
            // vorsichtigstes Urteil gewinnt: NO vor MAYBE vor MATCH
            perFingerprint.merge(judgement.fingerprint(), judgement,
                    (a, b) -> a.verdict().ordinal() >= b.verdict().ordinal() ? a : b);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<GroceryMatch> rows = new ArrayList<>();
        for (GroceryOffer offer : block) {
            Judgement judgement = perFingerprint.get(offer.getFingerprint());
            if (judgement == null || stored.containsKey(offer.getFingerprint())) {
                continue;
            }
            GroceryMatch row = new GroceryMatch();
            row.setProductId(product.getId());
            row.setFingerprint(offer.getFingerprint());
            row.setVerdict(GroceryMatch.Verdict.valueOf(judgement.verdict().name()));
            row.setReasonKind(judgement.reasonKind() == null ? null : judgement.reasonKind().name());
            row.setReason(judgement.reason());
            row.setDecidedBy(DecidedBy.CLAUDE);
            row.setDecidedAt(now);
            rows.add(row);
            stored.put(row.getFingerprint(), row);
        }
        if (!rows.isEmpty()) {
            matches.saveAll(rows);
        }
    }

    private List<MatchedOffer> collect(GroceryProduct product, List<GroceryOffer> candidates,
                                       Map<String, GroceryMatch> stored) {
        List<MatchedOffer> matched = new ArrayList<>();
        for (GroceryOffer offer : candidates) {
            GroceryMatch match = stored.get(offer.getFingerprint());
            if (match == null || match.getVerdict() == GroceryMatch.Verdict.NO) {
                continue;
            }
            if (ProductRules.excludes(product, CandidatePrefilter.offerText(offer))) {
                continue;
            }
            matched.add(new MatchedOffer(offer, Verdict.valueOf(match.getVerdict().name()),
                    match.getReasonKind() == null ? null : ReasonKind.valueOf(match.getReasonKind()),
                    match.getReason(), match.getDecidedBy() == DecidedBy.USER));
        }
        return matched;
    }

    @Transactional
    public void recordUserVerdict(long productId, String fingerprint, boolean fits) {
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException("Produkt", "id", productId);
        }
        GroceryMatch match = matches.findByProductIdAndFingerprint(productId, fingerprint)
                .orElseThrow(() -> new ResourceNotFoundException("Urteil", "fingerprint", fingerprint));
        if (fits) {
            if (match.getVerdict() != GroceryMatch.Verdict.MAYBE) {
                throw new IllegalStateException("Nur unsichere Treffer lassen sich bestätigen");
            }
            match.setVerdict(GroceryMatch.Verdict.MATCH);
        } else {
            match.setVerdict(GroceryMatch.Verdict.NO);
            match.setReasonKind(null);
            match.setReason(USER_REJECTED_REASON);
        }
        match.setDecidedBy(DecidedBy.USER);
        match.setDecidedAt(LocalDateTime.now(clock));
        matches.save(match);
    }
}
