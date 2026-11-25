package com.household.manager.repository;

import com.household.manager.blaulicht.BlaulichtEvent;
import com.household.manager.blaulicht.BlaulichtSourceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface BlaulichtEventRepository extends JpaRepository<BlaulichtEvent, Long> {

    boolean existsBySourceAndExternalId(BlaulichtSourceKey source, String externalId);

    /** Ob schon ein Fall dieser Quellmeldung gespeichert ist ({@code guid} oder {@code guid#n}). */
    @Query("select count(e) > 0 from BlaulichtEvent e where e.source = :source "
            + "and (e.externalId = :guid or e.externalId like concat(:guid, '#%'))")
    boolean existsForGuid(@Param("source") BlaulichtSourceKey source, @Param("guid") String guid);

    boolean existsByContentHash(String contentHash);

    /** Kandidaten für Liste und Nacht: veröffentlicht ab {@code from} (die Tatzeit liegt nie danach). */
    List<BlaulichtEvent> findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(LocalDateTime from);

    /** Retention: veröffentlicht vor {@code cutoff}. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from BlaulichtEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);
}
