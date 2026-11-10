package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A detected recurrence: cadence, typical amount, and the next expected date. */
@Data
@Builder
public class RecurrenceResult {
    private final RecurrenceInterval interval;
    private final BigDecimal expectedAmount;
    private final LocalDate nextDueDate;
    /** Date of the first term (after merging bookings within the term window). */
    private final LocalDate firstSeenDate;
    /** Date of the last term. */
    private final LocalDate lastSeenDate;
}
