package com.household.manager.repository;

import com.household.manager.laundry.LaundryDryingSession;
import com.household.manager.laundry.LaundrySessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LaundryDryingSessionRepository extends JpaRepository<LaundryDryingSession, Long> {

    Optional<LaundryDryingSession> findFirstByStatusInOrderByAskedAtDesc(Collection<LaundrySessionStatus> statuses);

    List<LaundryDryingSession> findTop10ByStatusInOrderByAskedAtDesc(Collection<LaundrySessionStatus> statuses);

    /** Nur Waschgaenge, die wirklich gestartet wurden (eine verfallene Frage ohne Start war keiner). */
    List<LaundryDryingSession> findTop10ByStatusInAndStartedAtIsNotNullOrderByAskedAtDesc(
            Collection<LaundrySessionStatus> statuses);

    Optional<LaundryDryingSession> findFirstByOrderByAskedAtDesc();
}
