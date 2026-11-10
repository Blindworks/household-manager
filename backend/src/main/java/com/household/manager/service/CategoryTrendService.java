package com.household.manager.service;

import com.household.manager.dto.CategoryTrendResponse;
import com.household.manager.finance.MonthlyCashflow;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ausgaben je Kategorie über mehrere Monate. Die Monatswerte kommen aus {@link CashflowService},
 * damit sie mit Übersicht, Verlauf und Budget übereinstimmen (Erstattungen sind verrechnet).
 */
@Service
@RequiredArgsConstructor
public class CategoryTrendService {

    static final int MAX_MONTHS = 36;
    static final String UNCATEGORIZED_NAME = "Unkategorisiert";
    static final String UNCATEGORIZED_COLOR = "#cfd8dc";

    private final CashflowService cashflowService;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public CategoryTrendResponse trend(YearMonth from, YearMonth to, Long accountId) {
        List<YearMonth> months = monthsBetween(from, to);
        Set<Long> expenseCategoryIds = cashflowService.expenseCategoryIds();

        Map<Long, BigDecimal[]> amountsByCategory = new LinkedHashMap<>();
        for (int i = 0; i < months.size(); i++) {
            MonthlyCashflow.Result cashflow = cashflowService.forMonth(months.get(i), accountId, expenseCategoryIds);
            int index = i;
            cashflow.expensesByCategory().forEach((categoryId, amount) ->
                    amountsByCategory.computeIfAbsent(categoryId, id -> zeros(months.size()))[index] = amount);
        }

        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        List<CategoryTrendResponse.Row> rows = new ArrayList<>();
        amountsByCategory.forEach((categoryId, amounts) -> rows.add(toRow(categoryId, amounts, categories)));
        rows.sort(Comparator.comparing(CategoryTrendResponse.Row::getTotal).reversed());

        return CategoryTrendResponse.builder()
                .months(months.stream().map(YearMonth::toString).toList())
                .categories(rows)
                .build();
    }

    private static List<YearMonth> monthsBetween(YearMonth from, YearMonth to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Der Startmonat liegt nach dem Endmonat.");
        }
        long count = ChronoUnit.MONTHS.between(from, to) + 1;
        if (count > MAX_MONTHS) {
            throw new IllegalArgumentException("Hoechstens " + MAX_MONTHS + " Monate auf einmal.");
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth cursor = from; !cursor.isAfter(to); cursor = cursor.plusMonths(1)) {
            months.add(cursor);
        }
        return months;
    }

    private static BigDecimal[] zeros(int size) {
        BigDecimal[] values = new BigDecimal[size];
        java.util.Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static CategoryTrendResponse.Row toRow(Long categoryId, BigDecimal[] amounts, Map<Long, Category> categories) {
        Category category = categoryId == null ? null : categories.get(categoryId);
        String name = categoryId == null ? UNCATEGORIZED_NAME : category != null ? category.getName() : "?";
        String color = categoryId == null ? UNCATEGORIZED_COLOR : category != null ? category.getColor() : null;
        BigDecimal total = java.util.Arrays.stream(amounts).reduce(BigDecimal.ZERO, BigDecimal::add);
        return CategoryTrendResponse.Row.builder()
                .categoryId(categoryId)
                .categoryName(name)
                .color(color)
                .amounts(List.of(amounts))
                .total(total)
                .build();
    }
}
