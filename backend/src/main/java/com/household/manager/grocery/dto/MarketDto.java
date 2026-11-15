package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;

public record MarketDto(Long id, Chain chain, String externalId, String name, String address,
                        Double lat, Double lng, boolean selected) {
}
