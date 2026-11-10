package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A recurring payment found for one merchant. {@code amounts} holds every distinct amount of
 * the series, including merged earlier prices and an attached fresh price, so stored rows can
 * be matched after a price change.
 */
public record DetectedSeries(String merchantKey, RecurrenceInterval interval, BigDecimal expectedAmount,
                             LocalDate firstSeenDate, LocalDate lastSeenDate, LocalDate nextDueDate,
                             Long categoryId, List<BigDecimal> amounts) {
}
