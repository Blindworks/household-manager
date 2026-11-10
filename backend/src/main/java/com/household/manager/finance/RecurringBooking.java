package com.household.manager.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One debit considered for recurrence detection; {@code amount} is negative. */
public record RecurringBooking(LocalDate date, BigDecimal amount, String merchantKey, Long categoryId) {
}
