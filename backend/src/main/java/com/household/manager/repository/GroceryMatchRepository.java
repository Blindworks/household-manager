package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroceryMatchRepository extends JpaRepository<GroceryMatch, Long> {

    List<GroceryMatch> findByProductId(Long productId);

    Optional<GroceryMatch> findByProductIdAndFingerprint(Long productId, String fingerprint);
}
