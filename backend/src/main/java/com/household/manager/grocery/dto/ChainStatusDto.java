package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;

/** Kette ohne Märkte (kettenweite Angebote) mit ihrem An/Aus-Schalter. */
public record ChainStatusDto(Chain chain, String name, boolean enabled) {
}
