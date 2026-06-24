package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** Result returned to the client after importing a statement file. */
@Data
@Builder
public class ImportSummaryResponse {
    private final long batchId;
    private final int importedCount;
    private final int skippedDuplicates;
    private final int failedCount;
    private final int uncategorizedCount;
    private final LocalDate dateFrom;
    private final LocalDate dateTo;
}
