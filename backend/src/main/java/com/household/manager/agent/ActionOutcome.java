package com.household.manager.agent;

/** Ergebnis einer Aktionsausfuehrung; {@code alreadySatisfied} heisst: Ziel war schon erreicht, kein Geraeteaufruf. */
public record ActionOutcome(boolean alreadySatisfied, String summary) {
}
