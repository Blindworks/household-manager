package com.household.manager.service;

import com.household.manager.finance.MonthlyCashflow;
import com.household.manager.model.entity.Category;
import com.household.manager.model.entity.CategoryKind;
import com.household.manager.repository.CategoryRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Liefert die verrechneten Monatssummen (Erstattungen in Ausgabenkategorien senken die Ausgaben)
 * für Übersicht, Verlauf und Budget — eine Quelle, damit die drei nicht auseinanderlaufen.
 */
@Service
@RequiredArgsConstructor
public class CashflowService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public MonthlyCashflow.Result forMonth(YearMonth month, Long accountId) {
        return forMonth(month, accountId, expenseCategoryIds());
    }

    @Transactional(readOnly = true)
    public MonthlyCashflow.Result forMonth(YearMonth month, Long accountId, Set<Long> expenseCategoryIds) {
        List<MonthlyCashflow.CategoryFlow> flows = transactionRepository
                .sumFlowsByCategory(month.atDay(1), month.atEndOfMonth(), accountId).stream()
                .map(CashflowService::toFlow)
                .toList();
        return MonthlyCashflow.compute(flows, expenseCategoryIds);
    }

    @Transactional(readOnly = true)
    public Set<Long> expenseCategoryIds() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getKind() == CategoryKind.EXPENSE)
                .map(Category::getId)
                .collect(Collectors.toSet());
    }

    private static MonthlyCashflow.CategoryFlow toFlow(Object[] row) {
        Long categoryId = row[0] != null ? ((Number) row[0]).longValue() : null;
        return new MonthlyCashflow.CategoryFlow(categoryId, toBigDecimal(row[1]), toBigDecimal(row[2]));
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
}
