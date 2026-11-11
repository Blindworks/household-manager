package com.household.manager.laundry;

/** Eine Messung der aufgelaufenen Trocknungsarbeit (kPa*h) eines Waschgangs. */
public record DryingSample(SampleKind kind, double load) {
}
