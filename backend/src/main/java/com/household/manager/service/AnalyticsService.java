package com.household.manager.service;

import com.household.manager.dto.CategorySpendItem;
import com.household.manager.dto.OverviewResponse;
import com.household.manager.dto.TrendPoint;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only aggregation for the overview KPIs, category breakdown and trends.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetService budgetService;

    @Transactional(readOnly = true)
    public OverviewResponse overview(YearMonth month, Long accountId) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        BigDecimal expenses = transactionRepository.sumExpenses(from, to, accountId).abs();
        BigDecimal income = transactionRepository.sumIncome(from, to, accountId);
        BigDecimal totalInvestments = transactionRepository.sumTransferOutflows(from, to, accountId).abs();
        Integer savingsRate = income.compareTo(BigDecimal.ZERO) > 0
                ? totalInvestments.multiply(BigDecimal.valueOf(100))
                        .divide(income, 0, RoundingMode.HALF_UP)
                        .intValue()
                : null;

        List<Category> allCategories = categoryRepository.findAll();
        Map<Long, String> categoryNames = allCategories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> categoryColors = allCategories.stream()
                .filter(c -> c.getColor() != null)
                .collect(Collectors.toMap(Category::getId, Category::getColor));

        List<CategorySpendItem> categories = new ArrayList<>();
        for (Object[] row : transactionRepository.sumAmountByCategory(from, to, accountId)) {
            Long categoryId = row[0] != null ? ((Number) row[0]).longValue() : null;
            BigDecimal sum = (BigDecimal) row[1];
            if (sum == null || sum.compareTo(BigDecimal.ZERO) >= 0) {
                continue; // only expenses (negative sums) appear in the breakdown
            }
            categories.add(CategorySpendItem.builder()
                    .categoryId(categoryId)
                    .categoryName(categoryId == null ? "Unkategorisiert"
                            : categoryNames.getOrDefault(categoryId, "?"))
                    .color(categoryId == null ? "#cfd8dc" : categoryColors.get(categoryId))
                    .amount(sum.abs())
                    .build());
        }
        categories.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return OverviewResponse.builder()
                .month(month.toString())
                .totalExpenses(expenses)
                .totalIncome(income)
                .balance(income.subtract(expenses))
                .totalInvestments(totalInvestments)
                .savingsRate(savingsRate)
                .budget(budgetService.getStatus(month))
                .categories(categories)
                .build();
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(YearMonth fromMonth, YearMonth toMonth, Long accountId) {
        List<TrendPoint> points = new ArrayList<>();
        YearMonth cursor = fromMonth;
        while (!cursor.isAfter(toMonth)) {
            LocalDate from = cursor.atDay(1);
            LocalDate to = cursor.atEndOfMonth();
            points.add(TrendPoint.builder()
                    .month(cursor.toString())
                    .expenses(transactionRepository.sumExpenses(from, to, accountId).abs())
                    .income(transactionRepository.sumIncome(from, to, accountId))
                    .build());
            cursor = cursor.plusMonths(1);
        }
        return points;
    }
}
