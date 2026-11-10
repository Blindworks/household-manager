package com.household.manager.service;

import com.household.manager.dto.BudgetRequest;
import com.household.manager.dto.BudgetResponse;
import com.household.manager.dto.BudgetStatusItem;
import com.household.manager.dto.BudgetStatusResponse;
import com.household.manager.finance.BudgetEvaluation;
import com.household.manager.finance.BudgetEvaluator;
import com.household.manager.finance.MonthlyCashflow;
import com.household.manager.model.entity.Budget;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.BudgetRepository;
import com.household.manager.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Manages monthly budgets (overall and per-category) and computes their status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetEvaluator evaluator;
    private final CashflowService cashflowService;

    @Transactional(readOnly = true)
    public List<BudgetResponse> getAll() {
        return budgetRepository.findAll().stream().map(this::toResponse).toList();
    }

    /** Upsert by scope: one overall budget, one per category. */
    @Transactional
    public BudgetResponse save(BudgetRequest request) {
        Optional<Budget> existing = (request.getCategoryId() == null)
                ? budgetRepository.findByCategoryIdIsNull()
                : budgetRepository.findByCategoryId(request.getCategoryId());

        Budget budget = existing.orElseGet(() -> Budget.builder()
                .categoryId(request.getCategoryId())
                .period("MONTHLY")
                .validFrom(LocalDate.now())
                .build());
        budget.setAmount(request.getAmount());
        return toResponse(budgetRepository.save(budget));
    }

    @Transactional
    public void delete(Long id) {
        budgetRepository.deleteById(id);
    }

    /** Compute the status of all budgets for the given month. */
    @Transactional(readOnly = true)
    public BudgetStatusResponse getStatus(YearMonth month) {
        MonthlyCashflow.Result cashflow = cashflowService.forMonth(month, null);

        BudgetStatusItem overall = budgetRepository.findByCategoryIdIsNull()
                .map(b -> {
                    BigDecimal spent = cashflow.totalExpenses();
                    BudgetEvaluation e = evaluator.evaluate(b.getAmount(), spent);
                    return item(null, "Gesamt", e);
                })
                .orElse(null);

        Map<Long, String> categoryNames = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        List<BudgetStatusItem> categories = new ArrayList<>();
        for (Budget b : budgetRepository.findByCategoryIdNotNull()) {
            BigDecimal spent = cashflow.expensesFor(b.getCategoryId());
            BudgetEvaluation e = evaluator.evaluate(b.getAmount(), spent);
            String name = categoryNames.getOrDefault(b.getCategoryId(), "?");
            categories.add(item(b.getCategoryId(), name, e));
        }

        return BudgetStatusResponse.builder().overall(overall).categories(categories).build();
    }

    private BudgetStatusItem item(Long categoryId, String name, BudgetEvaluation e) {
        return BudgetStatusItem.builder()
                .categoryId(categoryId).categoryName(name)
                .limit(e.getLimit()).spent(e.getSpent())
                .percent(e.getPercent()).status(e.getStatus())
                .build();
    }

    private BudgetResponse toResponse(Budget b) {
        return BudgetResponse.builder()
                .id(b.getId()).categoryId(b.getCategoryId())
                .period(b.getPeriod()).amount(b.getAmount())
                .build();
    }
}
