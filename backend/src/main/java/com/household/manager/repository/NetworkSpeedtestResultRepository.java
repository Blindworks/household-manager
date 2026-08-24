package com.household.manager.repository;

import com.household.manager.model.entity.NetworkSpeedtestResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NetworkSpeedtestResultRepository extends JpaRepository<NetworkSpeedtestResult, Long> {

    List<NetworkSpeedtestResult> findByTestedAtAfterOrderByTestedAtAsc(LocalDateTime after);

    Optional<NetworkSpeedtestResult> findTopBySuccessTrueOrderByTestedAtDesc();

    /**
     * Retention fuer alte Speedtest-Ergebnisse.
     *
     * <p>Bewusst eine Bulk-DML-Anweisung und kein abgeleiteter Delete (Muster
     * {@code WasteCollectionEventRepository.deleteFromDateOnwards}): ein abgeleiteter Delete
     * laedt die Zeilen nur und stellt sie ueber {@code em.remove()} in die Warteschlange, statt
     * sofort etwas an die DB zu schicken — dafuer braucht er eine aktive Transaktion, die eine
     * abgeleitete Query-Methode selbst nicht mitbringt. Diese Anweisung geht direkt als DML an
     * die DB und ist ueber {@code @Transactional} an genau diese Methode gebunden, unabhaengig
     * von der aufrufenden Job-Methode.
     */
    @Transactional
    @Modifying
    @Query("delete from NetworkSpeedtestResult r where r.testedAt < :cutoff")
    int deleteByTestedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
