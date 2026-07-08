package com.household.manager.service;

import com.household.manager.alexa.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachschnittstelle fuer Alexa-Durchsagen. Genutzt von Controller, Scheduler und
 * kuenftig anderen Services (interner Baustein fuer automatische Benachrichtigungen).
 */
@Service
@Slf4j
public class AlexaAnnouncementService {

    private static final String LOCALE = "de-DE";

    private final AlexaAuthService authService;
    private final AlexaApiClient apiClient;
    private final AlexaSequenceBuilder sequenceBuilder;

    public AlexaAnnouncementService(AlexaAuthService authService,
                                    AlexaApiClient apiClient,
                                    AlexaSequenceBuilder sequenceBuilder) {
        this.authService = authService;
        this.apiClient = apiClient;
        this.sequenceBuilder = sequenceBuilder;
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

        AlexaSession session = authService.getValidSession();
        List<AlexaRemoteDevice> allDevices = apiClient.listDevices(session);
        List<AlexaRemoteDevice> targets = allDevices.stream()
                .filter(d -> serialNumbers.contains(d.serialNumber()))
                .toList();

        if (targets.isEmpty()) {
            throw new AlexaException("Keines der gewaehlten Geraete wurde in der Cloud gefunden.");
        }

        if (mode == AlexaTtsMode.ANNOUNCE) {
            String body = sequenceBuilder.buildAnnouncement(
                    targets, session.getCustomerId(), LOCALE, text);
            apiClient.sendBehavior(session, body);
        } else {
            // SPEAK adressiert je genau ein Geraet -> pro Ziel ein Aufruf
            for (AlexaRemoteDevice target : targets) {
                String body = sequenceBuilder.buildSpeak(
                        target, session.getCustomerId(), LOCALE, text);
                apiClient.sendBehavior(session, body);
            }
        }
        log.info("Alexa-Ansage ({}) an {} Geraet(e) gesendet", mode, targets.size());
    }
}
