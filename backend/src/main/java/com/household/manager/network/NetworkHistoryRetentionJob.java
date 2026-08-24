package com.household.manager.network;

import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Haelt die Netzwerk-Historie klein: loescht alte Connectivity-Samples und
 * Speedtest-Ergebnisse. Laeuft nachts um 03:20 (bewusst nicht zur vollen Stunde,
 * dort laeuft der Speedtest).
 *
 * <p>Bewusst KEIN {@code @Transactional} auf {@link #retain()} oder den privaten
 * Loesch-Methoden: {@code deleteBySampledAtBefore}/{@code deleteByTestedAtBefore} sind als
 * {@code @Modifying}-Bulk-Deletes direkt am Repository transaktional annotiert (Muster
 * {@code WasteCollectionEventRepository.deleteFromDateOnwards}) und bringen damit ihre eigene,
 * in sich abgeschlossene Transaktion mit. Eine zusaetzliche aeussere Transaktion um beide
 * Aufrufe waere nicht nur ueberfluessig, sondern gefaehrlich: eine hier gefangene Exception aus
 * dem ersten Pfad wuerde eine gemeinsame Transaktion als rollback-only markieren und den
 * zweiten, an sich unabhaengigen Loeschpfad mitkippen. Ohne aeussere Transaktion und mit je
 * einem eigenen try/catch bleiben beide Pfade wirklich unabhaengig voneinander.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NetworkHistoryRetentionJob {

    /** Connectivity-Samples werden minuten-/sekundenweise erzeugt - kurze Aufbewahrung reicht. */
    private static final int CONNECTIVITY_RETENTION_DAYS = 30;
    /** Speedtest-Ergebnisse sind selten (wenige pro Tag) - lange Aufbewahrung fuer Trends. */
    private static final int SPEEDTEST_RETENTION_DAYS = 365;

    private final NetworkConnectivitySampleRepository connectivitySampleRepository;
    private final NetworkSpeedtestResultRepository speedtestResultRepository;
    private final Clock clock;

    @Scheduled(cron = "0 20 3 * * *")
    public void retain() {
        deleteOldConnectivitySamples();
        deleteOldSpeedtestResults();
    }

    private void deleteOldConnectivitySamples() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(CONNECTIVITY_RETENTION_DAYS);
            int deleted = connectivitySampleRepository.deleteBySampledAtBefore(cutoff);
            logDeletion("Connectivity-Samples", deleted);
        } catch (Exception e) {
            log.warn("Retention der Connectivity-Samples fehlgeschlagen", e);
        }
    }

    private void deleteOldSpeedtestResults() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(SPEEDTEST_RETENTION_DAYS);
            int deleted = speedtestResultRepository.deleteByTestedAtBefore(cutoff);
            logDeletion("Speedtest-Ergebnisse", deleted);
        } catch (Exception e) {
            log.warn("Retention der Speedtest-Ergebnisse fehlgeschlagen", e);
        }
    }

    private void logDeletion(String label, long deleted) {
        if (deleted > 0) {
            log.info("Netzwerk-Retention: {} {} geloescht", deleted, label);
        } else {
            log.debug("Netzwerk-Retention: keine {} zu loeschen", label);
        }
    }
}
