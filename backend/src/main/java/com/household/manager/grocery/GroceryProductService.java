package com.household.manager.grocery;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.grocery.dto.ProductDto;
import com.household.manager.grocery.dto.ProductRequest;
import com.household.manager.grocery.dto.VerdictRequest;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.MatchingService;
import com.household.manager.grocery.matching.ProductWords;
import com.household.manager.grocery.matching.SearchWordSuggester;
import com.household.manager.grocery.matching.SearchWordSuggester.Suggestion;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.repository.GroceryProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Produkte anlegen, ändern, löschen und beurteilen. Ändern und Urteilen nur für eigene und Haushaltsprodukte
 * (sonst 403, {@link GroceryOwners}). Bewusst ohne Transaktion: der Suchwort-Vorschlag ruft Claude.
 */
@Service
public class GroceryProductService {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_WORD_LIST_LENGTH = 500;
    static final BigDecimal MAX_NORMAL_PRICE = new BigDecimal("1000");

    private final GroceryProductRepository products;
    private final GroceryOwners owners;
    private final SearchWordSuggester suggester;
    private final MatchingService matching;
    private final AuditService audit;
    private final GroceryRunService runService;
    private final Clock clock;

    public GroceryProductService(GroceryProductRepository products, GroceryOwners owners,
                                 SearchWordSuggester suggester, MatchingService matching, AuditService audit,
                                 GroceryRunService runService, Clock clock) {
        this.products = products;
        this.owners = owners;
        this.suggester = suggester;
        this.matching = matching;
        this.audit = audit;
        this.runService = runService;
        this.clock = clock;
    }

    public List<ProductDto> list(String owner) {
        Predicate<Long> filter = owners.filter(owner);
        Optional<Long> me = owners.currentUserId();
        Map<Long, String> names = owners.displayNames();
        return products.findAll().stream()
                .filter(p -> filter.test(p.getOwnerUserId()))
                .sorted(Comparator.comparing(GroceryProduct::getId))
                .map(p -> toDto(p, me, names))
                .toList();
    }

    public ProductDto create(ProductRequest request) {
        Long me = owners.requireUserId();
        String name = validName(request.name());
        List<String> searchWords = normalized(request.searchWords());
        BaseUnit baseUnit = request.baseUnit();
        if (searchWords.isEmpty() || baseUnit == null) {
            Suggestion suggestion = suggester.suggest(name);
            if (searchWords.isEmpty()) {
                searchWords = normalized(suggestion.searchWords());
            }
            if (baseUnit == null) {
                baseUnit = suggestion.baseUnit();
            }
        }
        requireSearchWords(searchWords);
        GroceryProduct product = new GroceryProduct();
        product.setOwnerUserId(Boolean.TRUE.equals(request.household()) ? null : me);
        product.setActive(request.active() == null || request.active());
        product.setCreatedAt(LocalDateTime.now(clock));
        apply(product, name, baseUnit, searchWords, request);
        GroceryProduct saved = products.save(product);
        audit.record("grocery.product.create", describe(saved));
        runService.refreshProduct(saved.getId());
        return toDto(saved, Optional.of(me), owners.displayNames());
    }

    public ProductDto update(long id, ProductRequest request) {
        GroceryProduct product = find(id);
        owners.requireEditable(product.getOwnerUserId());
        String name = validName(request.name());
        List<String> searchWords = normalized(request.searchWords());
        if (searchWords.isEmpty()) {
            // wie beim Anlegen: leere Suchwörter schlägt der Server vor; die Einheit bleibt die gewählte
            searchWords = normalized(suggester.suggest(name).searchWords());
        }
        requireSearchWords(searchWords);
        BaseUnit baseUnit = request.baseUnit() != null ? request.baseUnit() : product.getBaseUnit();
        if (request.household() != null) {
            product.setOwnerUserId(request.household() ? null : owners.requireUserId());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
        if (!name.equals(product.getName()) || baseUnit != product.getBaseUnit()) {
            // anderes Produkt oder andere Einheit: die alte Schätzung passt nicht mehr, der nächste Lauf schätzt neu
            product.setEstimatedPricePerUnit(null);
            product.setEstimatedAt(null);
        }
        apply(product, name, baseUnit, searchWords, request);
        GroceryProduct saved = products.save(product);
        audit.record("grocery.product.update", describe(saved));
        runService.refreshProduct(saved.getId());
        return toDto(saved, owners.currentUserId(), owners.displayNames());
    }

    public void delete(long id) {
        GroceryProduct product = find(id);
        owners.requireEditable(product.getOwnerUserId());
        products.delete(product);
        audit.record("grocery.product.delete", describe(product));
        runService.refreshProduct(id);
    }

    /** „passt“/„passt nicht“; „passt“ auf ein nicht unsicheres Urteil ist ein Konflikt (409). */
    public void verdict(long id, VerdictRequest request) {
        GroceryProduct product = find(id);
        owners.requireEditable(product.getOwnerUserId());
        if (request.fingerprint() == null || request.fingerprint().isBlank()) {
            throw new IllegalArgumentException("Fingerabdruck fehlt");
        }
        if (request.fits() == null) {
            throw new IllegalArgumentException("Urteil fehlt");
        }
        try {
            matching.recordUserVerdict(id, request.fingerprint(), request.fits());
        } catch (IllegalStateException e) {
            throw new GroceryConflictException(e.getMessage());
        }
        audit.record("grocery.verdict", describe(product) + " " + request.fingerprint() + " -> "
                + (request.fits() ? "passt" : "passt nicht"));
        runService.refreshProduct(id);
    }

    private GroceryProduct find(long id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Produkt", "id", id));
    }

    private void apply(GroceryProduct product, String name, BaseUnit baseUnit, List<String> searchWords,
                       ProductRequest request) {
        if (baseUnit == null) {
            throw new IllegalArgumentException("Bezugseinheit fehlt");
        }
        product.setName(name);
        product.setBaseUnit(baseUnit);
        product.setSearchWords(joined(searchWords, "Suchwörter"));
        product.setMustWords(joined(normalized(request.mustWords()), "Muss-Wörter"));
        product.setExcludeWords(joined(normalized(request.excludeWords()), "Ausschluss-Wörter"));
        product.setNormalPricePerUnit(validNormalPrice(request.normalPricePerUnit()));
        product.setUpdatedAt(LocalDateTime.now(clock));
    }

    private static void requireSearchWords(List<String> searchWords) {
        if (searchWords.isEmpty()) {
            throw new IllegalArgumentException("Bitte Suchwörter angeben.");
        }
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Der Name muss 1 bis " + MAX_NAME_LENGTH + " Zeichen haben");
        }
        return name;
    }

    private static BigDecimal validNormalPrice(BigDecimal price) {
        if (price != null && (price.signum() <= 0 || price.compareTo(MAX_NORMAL_PRICE) > 0)) {
            throw new IllegalArgumentException("Der Normalpreis muss größer als 0 und höchstens 1000 € sein");
        }
        return price;
    }

    /** Wortliste über {@link ProductWords#split}: getrimmt, klein geschrieben, ohne Leer- und Doppeleinträge. */
    private static List<String> normalized(List<String> words) {
        if (words == null) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null) {
                ProductWords.split(word).forEach(w -> result.add(w.toLowerCase(Locale.ROOT)));
            }
        }
        return new ArrayList<>(result);
    }

    private static String joined(List<String> words, String label) {
        String value = String.join(",", words);
        if (value.length() > MAX_WORD_LIST_LENGTH) {
            throw new IllegalArgumentException(label + " dürfen zusammen höchstens " + MAX_WORD_LIST_LENGTH
                    + " Zeichen haben");
        }
        return value;
    }

    private static String describe(GroceryProduct product) {
        return product.getName() + " (id=" + product.getId() + ")";
    }

    static ProductDto toDto(GroceryProduct p, Optional<Long> me, Map<Long, String> names) {
        return new ProductDto(p.getId(), p.getOwnerUserId(), GroceryOwners.ownerName(p.getOwnerUserId(), names),
                p.getName(), p.getBaseUnit(), ProductWords.split(p.getSearchWords()),
                ProductWords.split(p.getMustWords()), ProductWords.split(p.getExcludeWords()),
                p.getNormalPricePerUnit(), p.getEstimatedPricePerUnit(), p.isActive(),
                GroceryOwners.isEditable(p.getOwnerUserId(), me));
    }
}
