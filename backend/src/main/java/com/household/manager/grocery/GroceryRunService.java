package com.household.manager.grocery;

import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.grocery.OfferCollectionService.CollectionResult;
import com.household.manager.grocery.dto.RunResultDto;
import com.household.manager.grocery.dto.RunStatusDto;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.GroceryRunLogService.RunOutcome;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.entity.GroceryRunLog;
import com.household.manager.grocery.matching.MatchingService;
import com.household.manager.grocery.matching.MatchingService.MatchedOffer;
import com.household.manager.grocery.matching.MatchingService.MatchingResult;
import com.household.manager.grocery.pricing.Deal;
import com.household.manager.grocery.pricing.DealEvaluator;
import com.household.manager.grocery.pricing.PriceObservationRecorder;
import com.household.manager.grocery.pricing.UsualPriceCalculator;
import com.household.manager.grocery.pricing.UsualPriceCalculator.UsualPrice;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryProductRepository;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Der Lauf der Supermarkt-Angebote: sammeln → zuordnen → Preisgedächtnis → Schnäppchen → Entitäten.
 * Die Schnäppchen leben in einem Speicher-Snapshot; nach einem Neustart werden sie aus der Datenbank neu
 * berechnet (gespeicherte Urteile, kein Claude-Aufruf). Bewusst ohne umschließende Transaktion.
 */
@Slf4j
@Service
public class GroceryRunService {

    static final Duration MANUAL_COOLDOWN = Duration.ofMinutes(10);
    static final String MANUAL_RUN_THREAD = "grocery-manual-run";

    /** Zugeordnete Angebote und Schnäppchen je Produkt-Id; {@code collectedAt} = jüngster Sammelzeitpunkt. */
    public record Snapshot(Map<Long, List<MatchedOffer>> matches, Map<Long, List<Deal>> deals,
                           LocalDateTime collectedAt) {
    }

    private record Computation(MatchingResult matching, Map<Long, List<Deal>> deals, int estimateCalls) {
    }

    private final OfferCollectionService collector;
    private final GroceryProductRepository products;
    private final GroceryOfferRepository offers;
    private final MatchingService matching;
    private final PriceObservationRecorder recorder;
    private final UsualPriceCalculator usualPrices;
    private final DealEvaluator evaluator;
    private final NormalPriceEstimates estimates;
    private final GrocerySettingsService settings;
    private final GroceryProperties properties;
    private final GroceryEntityReporter reporter;
    private final Clock clock;
    private final Executor manualRunExecutor;
    private final GroceryRunLogService runLog;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Instant> lastManualRun = new AtomicReference<>();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<RunStatusDto> status = new AtomicReference<>(RunStatusDto.idle());
    private final Object snapshotLock = new Object();

    @Autowired
    public GroceryRunService(OfferCollectionService collector, GroceryProductRepository products,
                             GroceryOfferRepository offers, MatchingService matching,
                             PriceObservationRecorder recorder, UsualPriceCalculator usualPrices,
                             DealEvaluator evaluator, NormalPriceEstimates estimates, GrocerySettingsService settings,
                             GroceryProperties properties,
                             GroceryEntityReporter reporter, Clock clock, GroceryRunLogService runLog) {
        this(collector, products, offers, matching, recorder, usualPrices, evaluator, estimates, settings, properties,
                reporter,
                clock, runLog, Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, MANUAL_RUN_THREAD);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    GroceryRunService(OfferCollectionService collector, GroceryProductRepository products,
                      GroceryOfferRepository offers, MatchingService matching,
                      PriceObservationRecorder recorder, UsualPriceCalculator usualPrices,
                      DealEvaluator evaluator, NormalPriceEstimates estimates, GrocerySettingsService settings,
                             GroceryProperties properties,
                      GroceryEntityReporter reporter, Clock clock, GroceryRunLogService runLog,
                      Executor manualRunExecutor) {
        this.manualRunExecutor = manualRunExecutor;
        this.runLog = runLog;
        this.collector = collector;
        this.products = products;
        this.offers = offers;
        this.matching = matching;
        this.recorder = recorder;
        this.usualPrices = usualPrices;
        this.evaluator = evaluator;
        this.estimates = estimates;
        this.settings = settings;
        this.properties = properties;
        this.reporter = reporter;
        this.clock = clock;
    }

    /**
     * Voller Lauf mit Claude-Budget aus den Properties.
     *
     * @throws TooManyRequestsException wenn bereits ein Lauf läuft
     */
    public RunResultDto run() {
        if (!running.compareAndSet(false, true)) {
            throw new TooManyRequestsException("Lauf läuft bereits");
        }
        try {
            markStarted();
            return runTracked(GroceryRunLog.Trigger.NIGHTLY);
        } finally {
            running.set(false);
        }
    }

    /** Laufstand für die Admin-Seite (nächtlicher und manueller Lauf). */
    public RunStatusDto runStatus() {
        return status.get();
    }

    /**
     * „Jetzt suchen“: prüft synchron Laufsperre und Mindestabstand (10 min, vor dem Lauf reserviert; ein laufender
     * Lauf verbraucht ihn nicht) und startet den Lauf dann im Hintergrund — ein voller Lauf dauert Minuten und
     * überschritte sonst die Proxy-Timeouts.
     *
     * @throws TooManyRequestsException wenn bereits ein Lauf läuft oder der Mindestabstand nicht erreicht ist
     */
    public void runManually() {
        if (!running.compareAndSet(false, true)) {
            throw new TooManyRequestsException("Lauf läuft bereits");
        }
        Instant reserved;
        try {
            reserved = reserveManualSlot();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        RunStatusDto before = status.get();
        markStarted();
        try {
            manualRunExecutor.execute(this::runManualInBackground);
        } catch (RejectedExecutionException e) {
            lastManualRun.compareAndSet(reserved, null);
            status.set(before);
            running.set(false);
            throw new IllegalStateException("Lauf konnte nicht gestartet werden");
        }
    }

    private void runManualInBackground() {
        try {
            runTracked(GroceryRunLog.Trigger.MANUAL);
        } catch (RuntimeException e) {
            log.warn("Manueller Supermarkt-Lauf fehlgeschlagen: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    void shutdownManualRuns() {
        if (manualRunExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private void markStarted() {
        RunStatusDto previous = status.get();
        status.set(new RunStatusDto(true, LocalDateTime.now(clock), previous.finishedAt(), previous.lastResult(),
                previous.lastError()));
    }

    /** Führt den Lauf aus und hält Ergebnis oder Fehler im Laufstand und im Laufprotokoll fest. */
    private RunResultDto runTracked(GroceryRunLog.Trigger trigger) {
        LocalDateTime startedAt = status.get().startedAt();
        try {
            RunOutcome outcome = runExclusive();
            RunResultDto result = toResult(outcome);
            LocalDateTime finishedAt = LocalDateTime.now(clock);
            status.set(new RunStatusDto(false, startedAt, finishedAt, result, null));
            runLog.record(trigger, startedAt, finishedAt, outcome, null);
            return result;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LocalDateTime finishedAt = LocalDateTime.now(clock);
            status.set(new RunStatusDto(false, startedAt, finishedAt, null, message));
            runLog.record(trigger, startedAt, finishedAt, null, message);
            throw e;
        }
    }

    /** Reserviert den Mindestabstand und liefert den reservierten Zeitpunkt (zum Zurückgeben). */
    private Instant reserveManualSlot() {
        Instant now = clock.instant();
        while (true) {
            Instant previous = lastManualRun.get();
            if (previous != null && now.isBefore(previous.plus(MANUAL_COOLDOWN))) {
                throw new TooManyRequestsException("Bitte 10 Minuten warten.");
            }
            if (lastManualRun.compareAndSet(previous, now)) {
                return now;
            }
        }
    }

    private RunOutcome runExclusive() {
        CollectionResult collection = collector.collect();
        List<GroceryProduct> active = products.findByActiveTrue();
        List<GroceryOffer> current = offers.findActiveFrom(LocalDate.now(clock));
        Computation computation = compute(active, current, properties.getMaxAiCallsPerRun(), true);
        storeSnapshot(new Snapshot(computation.matching().matches(), computation.deals(), collectedAt(current)));
        reportEntities();
        MatchingResult result = computation.matching();
        int dealCount = computation.deals().values().stream().mapToInt(List::size).sum();
        RunOutcome outcome = new RunOutcome(collection, result, computation.deals(), active,
                computation.estimateCalls());
        log.info("Supermarkt-Lauf: {} Angebote, {} Produkte, {} Claude-Aufrufe ({} Eingabe-/{} Ausgabe-Tokens), "
                        + "{} fehlgeschlagen, {} verschoben, {} Schnäppchen, {} Quelle(n) gestört",
                collection.offerCount(), active.size(), result.aiCallsUsed(), result.inputTokens(),
                result.outputTokens(), result.aiFailures(), result.productsDeferred(), dealCount,
                collection.failures().size());
        return outcome;
    }

    private static RunResultDto toResult(RunOutcome outcome) {
        MatchingResult result = outcome.matching();
        Map<String, String> failures = new LinkedHashMap<>();
        outcome.collection().failures().forEach((key, reason) -> failures.put(key.name(), reason));
        int dealCount = outcome.deals().values().stream().mapToInt(List::size).sum();
        return new RunResultDto(outcome.collection().offerCount(), result.aiCallsUsed() + outcome.estimateCalls(),
                result.productsDeferred(), dealCount, result.aiFailures(), failures);
    }

    /** Nach dem Start: Schnäppchen aus gespeicherten Angeboten und Urteilen, ohne Claude. Wirft nie. */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildFromDatabase() {
        try {
            Snapshot rebuilt = computeFromDatabase();
            storeSnapshot(rebuilt);
            reportEntities();
        } catch (RuntimeException e) {
            log.warn("Schnäppchen konnten nicht aus der Datenbank berechnet werden: {}", e.getMessage());
        }
    }

    private Snapshot computeFromDatabase() {
        List<GroceryOffer> current = offers.findActiveFrom(LocalDate.now(clock));
        Computation computation = compute(products.findByActiveTrue(), current, 0, false);
        return new Snapshot(computation.matching().matches(), computation.deals(), collectedAt(current));
    }

    /** Aktueller Stand; ist noch keiner da, wird er aus der Datenbank berechnet (leer, wenn das scheitert). */
    public Snapshot snapshot() {
        Snapshot current = snapshot.get();
        if (current != null) {
            return current;
        }
        try {
            Snapshot rebuilt = computeFromDatabase();
            synchronized (snapshotLock) {
                snapshot.compareAndSet(null, rebuilt);
            }
            return snapshot.get();
        } catch (RuntimeException e) {
            log.warn("Schnäppchen nicht berechenbar: {}", e.getMessage());
            return new Snapshot(Map.of(), Map.of(), null);
        }
    }

    /**
     * Berechnet die Schnäppchen eines Produkts nach Anlegen/Ändern/Löschen oder einem Urteil neu (ohne Claude) und
     * meldet die Entitäten. Ein gelöschtes oder inaktives Produkt fällt aus dem Snapshot.
     */
    public void refreshProduct(long productId) {
        try {
            Snapshot base = snapshot();
            GroceryProduct product = products.findById(productId).filter(GroceryProduct::isActive).orElse(null);
            Map<Long, List<MatchedOffer>> productMatches = Map.of();
            Map<Long, List<Deal>> productDeals = Map.of();
            if (product != null) {
                Computation computation = compute(List.of(product),
                        offers.findActiveFrom(LocalDate.now(clock)), 0, false);
                productMatches = computation.matching().matches();
                productDeals = computation.deals();
            }
            synchronized (snapshotLock) {
                Snapshot latest = Objects.requireNonNullElse(snapshot.get(), base);
                Map<Long, List<MatchedOffer>> matches = new LinkedHashMap<>(latest.matches());
                Map<Long, List<Deal>> deals = new LinkedHashMap<>(latest.deals());
                matches.remove(productId);
                deals.remove(productId);
                matches.putAll(productMatches);
                deals.putAll(productDeals);
                snapshot.set(new Snapshot(matches, deals, latest.collectedAt()));
            }
            reportEntities();
        } catch (RuntimeException e) {
            log.warn("Schnäppchen für Produkt {} nicht neu berechnet: {}", productId, e.getMessage());
        }
    }

    private Computation compute(List<GroceryProduct> productList, List<GroceryOffer> currentOffers, int aiBudget,
                                boolean recordObservations) {
        MatchingResult result = matching.matchAll(productList, currentOffers, aiBudget);
        // Nur im vollen Lauf (mit Claude-Budget): Normalpreis schätzen, bevor Schnäppchen bewertet werden
        int estimateCalls = aiBudget > 0 ? estimates.refreshDue(productList, result.matches()) : 0;
        LocalDate today = LocalDate.now(clock);
        int threshold = settings.dealThresholdPercent();
        Map<Long, List<Deal>> deals = new LinkedHashMap<>();
        for (GroceryProduct product : productList) {
            List<MatchedOffer> matched = result.matches().getOrDefault(product.getId(), List.of());
            try {
                if (recordObservations) {
                    recorder.record(product, matched);
                }
                UsualPrice usual = usualPrices.usual(product.getId(), today);
                deals.put(product.getId(), evaluator.evaluate(product, matched, usual, threshold));
            } catch (RuntimeException e) {
                log.warn("Schnäppchen für Produkt {} nicht bewertet: {}", product.getId(), e.getMessage());
                deals.put(product.getId(), List.of());
            }
        }
        return new Computation(result, deals, estimateCalls);
    }

    private void storeSnapshot(Snapshot next) {
        synchronized (snapshotLock) {
            snapshot.set(next);
        }
    }

    private void reportEntities() {
        try {
            reporter.report(snapshot.get().deals(), products.findAll());
        } catch (RuntimeException e) {
            log.warn("Schnäppchen-Entitäten nicht gemeldet: {}", e.getMessage());
        }
    }

    private static LocalDateTime collectedAt(List<GroceryOffer> currentOffers) {
        return currentOffers.stream().map(GroceryOffer::getCollectedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }
}
