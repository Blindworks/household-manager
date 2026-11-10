package com.household.manager.service;

import com.household.manager.dto.CategoryTrendResponse;
import com.household.manager.finance.MonthlyCashflow;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CategoryTrendServiceTest {

    private final CashflowService cashflow = mock(CashflowService.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CategoryTrendService service = new CategoryTrendService(cashflow, categories);

    private static MonthlyCashflow.Result result(Map<Long, BigDecimal> byCategory) {
        BigDecimal total = byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MonthlyCashflow.Result(total, BigDecimal.ZERO, byCategory);
    }

    private static Category category(long id, String name, String color) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setColor(color);
        return c;
    }

    @Test
    void buildsOneValuePerMonthAndCategorySortedByTotal() {
        when(cashflow.expenseCategoryIds()).thenReturn(Set.of(1L, 2L));
        when(cashflow.forMonth(eq(YearMonth.of(2026, 9)), eq(null), any()))
                .thenReturn(result(Map.of(1L, new BigDecimal("100"), 2L, new BigDecimal("30"))));
        when(cashflow.forMonth(eq(YearMonth.of(2026, 10)), eq(null), any()))
                .thenReturn(result(Map.of(2L, new BigDecimal("500"))));
        when(categories.findAll()).thenReturn(List.of(
                category(1, "Lebensmittel", "#00ff00"), category(2, "Miete", null)));

        CategoryTrendResponse response = service.trend(YearMonth.of(2026, 9), YearMonth.of(2026, 10), null);

        assertEquals(List.of("2026-09", "2026-10"), response.getMonths());
        assertEquals(2, response.getCategories().size());
        CategoryTrendResponse.Row first = response.getCategories().get(0);
        assertEquals("Miete", first.getCategoryName());
        assertEquals(0, new BigDecimal("530").compareTo(first.getTotal()));
        assertEquals(0, new BigDecimal("30").compareTo(first.getAmounts().get(0)));
        CategoryTrendResponse.Row second = response.getCategories().get(1);
        assertEquals("Lebensmittel", second.getCategoryName());
        assertEquals("#00ff00", second.getColor());
        assertEquals(0, BigDecimal.ZERO.compareTo(second.getAmounts().get(1)), "Monat ohne Ausgaben ist 0");
        verify(cashflow, times(1)).expenseCategoryIds();
    }

    @Test
    void uncategorizedHasNullIdAndOwnLabel() {
        when(cashflow.expenseCategoryIds()).thenReturn(Set.of());
        java.util.HashMap<Long, BigDecimal> map = new java.util.HashMap<>();
        map.put(null, new BigDecimal("12.50"));
        when(cashflow.forMonth(any(), any(), any())).thenReturn(result(map));
        when(categories.findAll()).thenReturn(List.of());

        CategoryTrendResponse response = service.trend(YearMonth.of(2026, 10), YearMonth.of(2026, 10), 7L);

        CategoryTrendResponse.Row row = response.getCategories().get(0);
        assertNull(row.getCategoryId());
        assertEquals("Unkategorisiert", row.getCategoryName());
        verify(cashflow).forMonth(eq(YearMonth.of(2026, 10)), eq(7L), any());
    }

    @Test
    void rejectsReversedRangeAndTooManyMonths() {
        assertThrows(IllegalArgumentException.class,
                () -> service.trend(YearMonth.of(2026, 10), YearMonth.of(2026, 9), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.trend(YearMonth.of(2023, 1), YearMonth.of(2026, 1), null));
    }
}
