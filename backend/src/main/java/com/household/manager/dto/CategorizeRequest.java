package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for assigning a category to a transaction by hand. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizeRequest {
    @NotNull
    private Long categoryId;
}
