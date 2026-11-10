package com.household.manager.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One debit considered for recurrence detection; {@code amount} is negative. {@code transactionId}
 * (nullable) only breaks ties between bookings of the same day, so "latest" is deterministic.
 */
public record RecurringBooking(LocalDate date, BigDecimal amount, String merchantKey, Long categoryId,
                               Long transactionId) {
}
