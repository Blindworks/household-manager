package com.household.manager.laundry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lernt die benoetigte Trocknungsarbeit aus den letzten Waschgaengen. */
public final class DryingCalibration {

    private DryingCalibration() {
    }

    /** @param lastSessionsNewestFirst Proben je Waschgang, juengster zuerst; nur die ersten LEARN_WINDOW zaehlen */
    public static double requiredLoad(List<List<DryingSample>> lastSessionsNewestFirst) {
        List<List<DryingSample>> sessions = lastSessionsNewestFirst.subList(
                0, Math.min(LaundryRules.LEARN_WINDOW, lastSessionsNewestFirst.size()));

        List<Double> confirmed = loads(sessions, SampleKind.CONFIRMED);
        List<Double> basis = new ArrayList<>(confirmed);
        if (confirmed.size() < LaundryRules.MIN_CONFIRMED) {
            basis.addAll(loads(sessions, SampleKind.WEAK));
        }

        double largestLowerBound = sessions.stream()
                .filter(session -> session.stream().noneMatch(s -> s.kind() == SampleKind.CONFIRMED))
                .flatMap(List::stream)
                .filter(s -> s.kind() == SampleKind.LOWER_BOUND)
                .mapToDouble(DryingSample::load)
                .max()
                .orElse(Double.NaN);

        if (basis.isEmpty()) {
            return Double.isNaN(largestLowerBound)
                    ? LaundryRules.DEFAULT_REQUIRED_LOAD
                    : Math.max(LaundryRules.DEFAULT_REQUIRED_LOAD, largestLowerBound);
        }
        double median = median(basis);
        return !Double.isNaN(largestLowerBound) && largestLowerBound > median ? largestLowerBound : median;
    }

    private static List<Double> loads(List<List<DryingSample>> sessions, SampleKind kind) {
        return sessions.stream().flatMap(List::stream)
                .filter(s -> s.kind() == kind).map(DryingSample::load).toList();
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
