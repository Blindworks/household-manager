package com.household.manager.network;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Fuehrt eine Roh-Messung gegen einen externen Speedtest-Dienst aus.
 * Wirft bei Netzfehlern (Zeitueberschreitung, Verbindungsabbruch, ...) -
 * die Uebersetzung in ein Ergebnis/eine Fehlermeldung uebernimmt {@link NetworkSpeedtestService}.
 */
public interface SpeedtestClient {

    /**
     * @param budget Zeitfenster, ueber das gemessen wird
     * @return Mbit/s, gemessen ueber das Zeitbudget
     */
    BigDecimal measureDownloadMbps(Duration budget) throws IOException, InterruptedException;

    /**
     * @param budget Zeitfenster, ueber das gemessen wird
     * @return Mbit/s, gemessen ueber das Zeitbudget
     */
    BigDecimal measureUploadMbps(Duration budget) throws IOException, InterruptedException;
}
