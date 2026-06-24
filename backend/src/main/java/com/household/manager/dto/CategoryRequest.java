package com.household.manager.dto;

import com.household.manager.model.entity.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {
    @NotBlank(message = "Name is required")
    private String name;
    @NotNull(message = "Kind is required")
    private CategoryKind kind;
    private String color;
    private Long parentId;
}
