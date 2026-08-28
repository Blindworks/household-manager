package com.household.manager.repository;

import com.household.manager.model.entity.PetSupply;
import com.household.manager.model.entity.PetSupplyTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PetSupplyTransactionRepository extends JpaRepository<PetSupplyTransaction, Long> {

    List<PetSupplyTransaction> findBySupplyOrderByOccurredAtDescIdDesc(PetSupply supply, Pageable pageable);
}
