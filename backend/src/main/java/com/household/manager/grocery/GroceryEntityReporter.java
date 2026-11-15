package com.household.manager.grocery;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.grocery.entity.GroceryProduct;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.Deal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spiegelt die Schnäppchen als {@code sensor.grocery_deals_<userId>} (je Person mit Produkten) und
 * {@code sensor.grocery_deals_household} (immer). State = Anzahl, Attribut {@code items} (höchstens 5).
 * Bewusst ohne {@code deviceClass} und {@code unit}; wird nie {@code unavailable}. Wirft nie.
 */
@Slf4j
@Component
public class GroceryEntityReporter {

    static final int MAX_ITEMS = 5;
    static final String HOUSEHOLD_REF = "deals_household";

    private static final Comparator<Deal> ORDER = Comparator
            .comparing((Deal d) -> d.match().verdict() == Verdict.MATCH ? 0 : 1)
            .thenComparing(Comparator.comparingInt(Deal::savingPercent).reversed());

    private final EntityStateService entityStateService;
    private final GroceryOwners owners;

    public GroceryEntityReporter(EntityStateService entityStateService, GroceryOwners owners) {
        this.entityStateService = entityStateService;
        this.owners = owners;
    }

    /** {@code sensor.grocery_deals_<userId>}; die Ref {@code deals_<id>} ergibt über EntityIds genau diese Form. */
    public static String userEntityId(long userId) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.GROCERY, "deals_" + userId, null);
    }

    public static String householdEntityId() {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.GROCERY, HOUSEHOLD_REF, null);
    }

    /** Ein Schnäppchen mit dem Namen seines Produkts. */
    private record NamedDeal(String productName, Deal deal) {
    }

    public void report(Map<Long, List<Deal>> dealsByProduct, List<GroceryProduct> products) {
        try {
            List<NamedDeal> household = new ArrayList<>();
            Map<Long, List<NamedDeal>> userDeals = new TreeMap<>();
            for (GroceryProduct product : products) {
                List<NamedDeal> deals = dealsByProduct.getOrDefault(product.getId(), List.of()).stream()
                        .map(d -> new NamedDeal(product.getName(), d)).toList();
                if (product.getOwnerUserId() == null) {
                    household.addAll(deals);
                } else {
                    userDeals.computeIfAbsent(product.getOwnerUserId(), k -> new ArrayList<>()).addAll(deals);
                }
            }
            Map<Long, String> names = userDeals.isEmpty() ? Map.of() : owners.displayNames();
            userDeals.forEach((userId, deals) -> reportOne(userEntityId(userId), "deals_" + userId,
                    "Schnäppchen " + GroceryOwners.ownerName(userId, names), deals));
            reportOne(householdEntityId(), HOUSEHOLD_REF, "Schnäppchen " + GroceryOwners.HOUSEHOLD_NAME, household);
        } catch (RuntimeException e) {
            log.warn("Schnäppchen-Entitäten nicht gemeldet: {}", e.getMessage());
        }
    }

    private void reportOne(String entityId, String sourceRef, String friendlyName, List<NamedDeal> deals) {
        try {
            List<String> items = deals.stream()
                    .sorted(Comparator.comparing(NamedDeal::deal, ORDER)).limit(MAX_ITEMS)
                    .map(d -> item(d.productName(), d.deal())).toList();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("items", items);
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(entityId)
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.GROCERY)
                    .sourceRef(sourceRef)
                    .friendlyName(friendlyName)
                    .state(String.valueOf(deals.size()))
                    .attributes(attributes)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Schnäppchen-Entität {} nicht gemeldet: {}", entityId, e.getMessage());
        }
    }

    /** „Cola Zero: Penny 1,43 €/l“. */
    private static String item(String productName, Deal deal) {
        return productName + ": " + deal.match().offer().getChain().displayName() + " "
                + formatPrice(deal.basePrice()) + " €/" + unitLabel(deal.match().offer().getBaseUnit());
    }

    static String formatPrice(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY));
        return format.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    static String unitLabel(BaseUnit unit) {
        if (unit == null) {
            return "Einheit";
        }
        return switch (unit) {
            case LITER -> "l";
            case KILOGRAM -> "kg";
            case PIECE -> "Stück";
        };
    }
}
