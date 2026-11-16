package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GroceryMatchRepository extends JpaRepository<GroceryMatch, Long> {

    List<GroceryMatch> findByProductId(Long productId);

    Optional<GroceryMatch> findByProductIdAndFingerprint(Long productId, String fingerprint);

    /** Retention: Urteile eines Urhebers vor {@code cutoff}. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from GroceryMatch m where m.decidedBy = :decidedBy and m.decidedAt < :cutoff")
    int deleteDecidedBefore(@Param("decidedBy") GroceryMatch.DecidedBy decidedBy,
                            @Param("cutoff") LocalDateTime cutoff);
}
