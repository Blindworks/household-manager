package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankAccountRequest {
    @NotBlank(message = "Name is required")
    private String name;
    private String iban;
    @NotBlank(message = "Currency is required")
    private String currency;
}
