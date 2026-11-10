package com.household.manager.repository;

import com.household.manager.model.entity.RecurringPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecurringPaymentRepository extends JpaRepository<RecurringPayment, Long> {

    /** All rows of an account, dismissed ones included (detection must keep matching them). */
    List<RecurringPayment> findByAccountId(Long accountId);

    List<RecurringPayment> findByDismissedFalse();

    List<RecurringPayment> findByConfirmedAndDismissedFalse(boolean confirmed);
}
