package com.household.manager.grocery.dto;

import java.util.List;

/** Ein Lauf im Detail; {@code judgementsTruncated}, wenn mehr Urteile entstanden, als ausgeliefert werden. */
public record RunLogDetailDto(RunLogSummaryDto run, List<RunSourceLine> sources, List<RunProductLine> products,
                              List<RunJudgementLine> judgements, boolean judgementsTruncated) {
}
