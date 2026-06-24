package com.household.manager.dto;

import com.household.manager.model.entity.CategoryKind;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponse {
    private final Long id;
    private final String name;
    private final CategoryKind kind;
    private final String color;
    private final boolean system;
    private final Long parentId;
}
