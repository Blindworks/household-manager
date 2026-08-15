package com.household.manager.repository;

import com.household.manager.model.entity.PetFoodTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PetFoodTransactionRepository extends JpaRepository<PetFoodTransaction, Long> {

    List<PetFoodTransaction> findByOrderByOccurredAtDescIdDesc(Pageable pageable);
}
