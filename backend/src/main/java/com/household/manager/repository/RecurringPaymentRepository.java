package com.household.manager.repository;

import com.household.manager.model.entity.RecurrenceInterval;
import com.household.manager.model.entity.RecurringPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {

    List<RecurringPayment> findByConfirmed(boolean confirmed);

    Optional<RecurringPayment> findByAccountIdAndCounterpartyPatternAndInterval(
            Long accountId, String counterpartyPattern,
            RecurrenceInterval interval);
}
