package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ein einzelner Zeit/Wert-Punkt einer Messreihe. */
@Getter
@Builder
public class TimeValue {
    private final LocalDateTime time;
    private final BigDecimal value;
}
