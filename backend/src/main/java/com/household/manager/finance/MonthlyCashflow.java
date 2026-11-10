package com.household.manager.finance;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Einzige Definition von "Ausgaben" und "Einnahmen" eines Monats.
 *
 * <p>In einer Kategorie der Art Ausgabe werden Eingänge mit den Abbuchungen verrechnet: eine
 * Erstattung (Mietanteil der Mitbewohnerin, Retoure) senkt die Ausgaben der Kategorie und zählt
 * nicht als Einnahme. Je Kategorie wird bei 0 abgeschnitten, ein Überschuss zählt als Einnahme.
 * Alle anderen Buchungen (unkategorisiert, Einnahmenkategorien) werden nach dem Vorzeichen
 * eingeordnet. Umbuchungskategorien sind nicht Teil der Eingabe.
 */
public final class MonthlyCashflow {

    private MonthlyCashflow() {
    }

    /**
     * Summen einer Kategorie im Monat.
     *
     * @param categoryId Kategorie, {@code null} für unkategorisiert
     * @param outflow    Summe der negativen Beträge (≤ 0)
     * @param inflow     Summe der positiven Beträge (≥ 0)
     */
    public record CategoryFlow(Long categoryId, BigDecimal outflow, BigDecimal inflow) {
    }

    /**
     * @param expensesByCategory Ausgaben je Kategorie als positive Zahl, nur Kategorien mit Ausgaben > 0;
     *                           Schlüssel {@code null} = unkategorisiert
     */
    public record Result(BigDecimal totalExpenses, BigDecimal totalIncome, Map<Long, BigDecimal> expensesByCategory) {

        public BigDecimal expensesFor(Long categoryId) {
            return expensesByCategory.getOrDefault(categoryId, BigDecimal.ZERO);
        }
    }

    public static Result compute(Collection<CategoryFlow> flows, Set<Long> expenseCategoryIds) {
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        Map<Long, BigDecimal> expensesByCategory = new HashMap<>();

        for (CategoryFlow flow : flows) {
            BigDecimal outflow = orZero(flow.outflow()).abs();
            BigDecimal inflow = orZero(flow.inflow());
            BigDecimal expense;
            BigDecimal income;
            if (flow.categoryId() != null && expenseCategoryIds.contains(flow.categoryId())) {
                BigDecimal net = outflow.subtract(inflow);
                expense = net.max(BigDecimal.ZERO);
                income = net.negate().max(BigDecimal.ZERO);
            } else {
                expense = outflow;
                income = inflow;
            }
            if (expense.signum() > 0) {
                expensesByCategory.merge(flow.categoryId(), expense, BigDecimal::add);
            }
            totalExpenses = totalExpenses.add(expense);
            totalIncome = totalIncome.add(income);
        }
        return new Result(totalExpenses, totalIncome, Collections.unmodifiableMap(expensesByCategory));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
