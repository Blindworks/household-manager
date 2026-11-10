package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

/** Outcome of a recurring-payment detection run: new candidates and continued stored rows. */
@Data
@Builder
public class RecurringDetectionResponse {
    private final int created;
    private final int updated;
}
