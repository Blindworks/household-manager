package com.household.manager.repository;

import com.household.manager.model.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    boolean existsByDedupHash(String dedupHash);

    List<Transaction> findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
            Long accountId, LocalDate from, LocalDate to);

    List<Transaction> findByBookingDateBetweenOrderByBookingDateDesc(LocalDate from, LocalDate to);

    List<Transaction> findByCategoryIdIsNull();

    List<Transaction> findByCategoryIdIsNullAndManuallyCategorizedFalse();

    List<Transaction> findByManuallyCategorizedFalse();

    Optional<Transaction> findByDedupHash(String dedupHash);

    /**
     * Abbuchungen und Eingänge je Nicht-TRANSFER-Kategorie im Zeitraum, als
     * [categoryId, Summe der negativen Beträge, Summe der positiven Beträge].
     * Verrechnet wird in {@link com.household.manager.finance.MonthlyCashflow}.
     */
    @Query("""
            SELECT t.categoryId,
                   COALESCE(SUM(CASE WHEN t.amount < 0 THEN t.amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN t.amount > 0 THEN t.amount ELSE 0 END), 0)
            FROM Transaction t
            WHERE t.bookingDate BETWEEN :from AND :to
              AND (:accountId IS NULL OR t.accountId = :accountId)
              AND (t.categoryId IS NULL OR t.categoryId NOT IN
                   (SELECT c.id FROM Category c WHERE c.kind = com.household.manager.model.entity.CategoryKind.TRANSFER))
            GROUP BY t.categoryId
            """)
    List<Object[]> sumFlowsByCategory(@Param("from") LocalDate from,
                                      @Param("to") LocalDate to,
                                      @Param("accountId") Long accountId);

    /** Total outflow (negative amounts) for TRANSFER categories — wealth-building transfers (investments, savings). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.amount < 0
              AND (:accountId IS NULL OR t.accountId = :accountId)
              AND t.bookingDate BETWEEN :from AND :to
              AND t.categoryId IN
                  (SELECT c.id FROM Category c WHERE c.kind = com.household.manager.model.entity.CategoryKind.TRANSFER)
            """)
    BigDecimal sumTransferOutflows(@Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   @Param("accountId") Long accountId);
}
