package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwAddressDto(String street, String postalCode, String city) {
}
