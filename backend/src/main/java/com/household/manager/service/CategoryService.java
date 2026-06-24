package com.household.manager.service;

import com.household.manager.dto.CategoryRequest;
import com.household.manager.dto.CategoryResponse;
import com.household.manager.model.entity.Category;
import com.household.manager.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository repository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        Category category = repository.save(Category.builder()
                .name(request.getName())
                .kind(request.getKind())
                .color(request.getColor())
                .parentId(request.getParentId())
                .system(false)
                .build());
        return toResponse(category);
    }

    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category id: " + id));
        category.setName(request.getName());
        category.setKind(request.getKind());
        category.setColor(request.getColor());
        category.setParentId(request.getParentId());
        return toResponse(repository.save(category));
    }

    @Transactional
    public void delete(Long id) {
        Category category = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category id: " + id));
        if (category.isSystem()) {
            throw new IllegalArgumentException("System-Kategorien können nicht gelöscht werden.");
        }
        repository.deleteById(id);
    }

    private CategoryResponse toResponse(Category c) {
        return CategoryResponse.builder()
                .id(c.getId()).name(c.getName()).kind(c.getKind())
                .color(c.getColor()).system(c.isSystem()).parentId(c.getParentId())
                .build();
    }
}
