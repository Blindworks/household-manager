package com.household.manager.service;

import com.household.manager.alexa.AlexaException;
import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaTtsMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachschnittstelle fuer Alexa-Durchsagen. Genutzt von Controller, Scheduler und
 * kuenftig anderen Services (interner Baustein fuer automatische Benachrichtigungen).
 * Die eigentliche Ausgabe uebernimmt der alexa-remote2-Sidecar.
 */
@Service
@Slf4j
public class AlexaAnnouncementService {

    private final AlexaSidecarClient sidecarClient;

    public AlexaAnnouncementService(AlexaSidecarClient sidecarClient) {
        this.sidecarClient = sidecarClient;
    }

    /**
     * Spricht {@code text} auf den Geraeten mit den angegebenen Seriennummern.
     *
     * @param text          zu sprechender Text (nicht leer)
     * @param serialNumbers Ziel-Seriennummern (mindestens eine)
     * @param mode          SPEAK (ein Geraet, ohne Ton) oder ANNOUNCE (mit Signalton)
     */
    public void announce(String text, List<String> serialNumbers, AlexaTtsMode mode) {
        if (text == null || text.isBlank()) {
            throw new AlexaException("Ansagetext darf nicht leer sein.");
        }
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            throw new AlexaException("Es wurde kein Zielgeraet ausgewaehlt.");
        }

        if (mode == AlexaTtsMode.ANNOUNCE) {
            sidecarClient.announce(serialNumbers, text);
        } else {
            // SPEAK adressiert je genau ein Geraet -> pro Ziel ein Aufruf
            for (String serialNumber : serialNumbers) {
                sidecarClient.speak(serialNumber, text);
            }
        }
        log.info("Alexa-Ansage ({}) an {} Geraet(e) gesendet", mode, serialNumbers.size());
    }

    /**
     * Spielt einen kurzen Ton (z. B. {@code amzn_sfx_doorbell_chime_01}) auf allen Zielgeraeten
     * zugleich — ein einziger Aufruf an Amazon, ohne Sprachausgabe.
     */
    public void playSound(String soundId, List<String> serialNumbers) {
        if (soundId == null || soundId.isBlank()) {
            throw new AlexaException("Es wurde kein Ton angegeben.");
        }
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            throw new AlexaException("Es wurde kein Zielgeraet ausgewaehlt.");
        }
        sidecarClient.playSound(serialNumbers, soundId);
        log.info("Alexa-Ton {} an {} Geraet(e) gesendet", soundId, serialNumbers.size());
    }
}
