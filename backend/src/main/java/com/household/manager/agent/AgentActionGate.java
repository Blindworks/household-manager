package com.household.manager.agent;

import com.household.manager.model.entity.AgentProposal;
import com.household.manager.repository.AgentProposalRepository;
import com.household.manager.tapo.hub.ThermostatMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Der einzige Weg, auf dem eine vom Modell gewünschte Aktion das Haus erreicht. Erst die harten
 * Leitplanken (unabhängig von Prompt und Richtlinie, damit ein manipuliertes Modell sie nicht
 * umgehen kann), dann die Richtlinie der Aktionsklasse: DIRECT führt aus, PROPOSE stellt einen
 * Vorschlag zur Freigabe.
 *
 * <p>Vor der Richtlinie wird das Ziel frisch gegen den Ist-Zustand geprüft ({@link AgentActionExecutor#isSatisfied}):
 * ein unbekanntes oder erfundenes Ziel (das Modell kennt Entity-IDs nur aus dem Snapshot) und ein nicht
 * schaltbarer Zustand enden als Tool-Fehler fürs Modell — ohne Vorschlag und ohne Telegram-Nachricht —, und
 * ein bereits erreichtes Ziel erzeugt weder Vorschlag noch Ausführung. Das gilt für DIRECT und PROPOSE gleich.
 *
 * <p>Duplikat- und Limitprüfung gelten nur im Vorschlagspfad: direkte Aktionen belegen keinen
 * offenen Vorschlag, und eine Benachrichtigung soll nie am Limit offener Vorschläge scheitern. Das Limit
 * zählt nur zugestellte Vorschläge — in der Ruhezeit zurückgehaltene hat noch niemand gesehen. Die
 * Doppel-Ziel-Sperre zählt dagegen auch sie, sonst stünden morgens zwei Fragen fürs selbe Ziel da.
 */
@Component
@RequiredArgsConstructor
public class AgentActionGate {

    static final double MIN_TARGET = 5.0;
    static final double MAX_TARGET = 24.0;

    private final AgentPolicyService policyService;
    private final AgentProposalService proposalService;
    private final AgentProposalRepository repository;
    private final AgentProperties properties;
    private final AgentActionExecutor executor;

    /**
     * Wirft {@link IllegalArgumentException} bei ungültiger Aktion oder unbekanntem/nicht schaltbarem Ziel —
     * das Tool-Register macht daraus einen Tool-Fehler, den das Modell sieht.
     */
    public GateResult submit(AgentAction action) {
        validate(action);
        if (action.actionClass() != ActionClass.NOTIFY && executor.isSatisfied(action)) {
            return new GateResult(action.describe() + " ist bereits so — nichts zu tun.", null, false);
        }
        if (policyService.policyFor(action.actionClass()) == ActionPolicy.DIRECT) {
            AgentProposal row = proposalService.executeDirect(action);
            return switch (row.getStatus()) {
                case FAILED -> new GateResult("Fehlgeschlagen: " + row.getError(), row.getId(), true);
                case PENDING -> new GateResult("Nachricht wird nach der Ruhezeit zugestellt.", row.getId(), false);
                default -> new GateResult("Ausgeführt: " + action.describe(), row.getId(), true);
            };
        }
        if (repository.existsByStatusAndTarget(ProposalStatus.PENDING, action.target())) {
            return new GateResult("Für " + action.target()
                    + " ist bereits ein Vorschlag offen — nicht erneut vorschlagen.", null, false);
        }
        if (repository.countByStatusAndDeliveredAtIsNotNull(ProposalStatus.PENDING) >= properties.getMaxOpenProposals()) {
            return new GateResult("Es sind bereits " + properties.getMaxOpenProposals()
                    + " Vorschläge offen — keine weiteren, bis entschieden wurde.", null, false);
        }
        AgentProposal row = proposalService.propose(action);
        return new GateResult("Als Vorschlag #" + row.getId()
                + " gestellt (Telegram). Nicht erneut vorschlagen.", row.getId(), false);
    }

    private void validate(AgentAction action) {
        if (action.reason() == null || action.reason().isBlank()) {
            throw new IllegalArgumentException("reason fehlt");
        }
        switch (action.actionClass()) {
            case HEATING -> validateHeating(action);
            case SWITCH, MODE -> {
                String state = action.stringParam("state");
                if (!"on".equals(state) && !"off".equals(state)) {
                    throw new IllegalArgumentException("state muss on oder off sein");
                }
            }
            case NOTIFY -> {
                String message = action.stringParam("message");
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException("message fehlt");
                }
            }
            case LOCK -> { /* es gibt nur Verriegeln, nichts zu begrenzen */ }
        }
    }

    private void validateHeating(AgentAction action) {
        Double target = action.doubleParam("targetTemp");
        String mode = action.stringParam("mode");
        if (target == null && mode == null) {
            throw new IllegalArgumentException("targetTemp oder mode angeben");
        }
        if (target != null && (!Double.isFinite(target) || target < MIN_TARGET || target > MAX_TARGET)) {
            throw new IllegalArgumentException("Zieltemperatur " + target + " liegt außerhalb "
                    + MIN_TARGET + "–" + MAX_TARGET + " °C");
        }
        if (mode != null) {
            try {
                ThermostatMode.valueOf(mode);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("mode muss MANUAL, OFF oder FROST_PROTECTION sein");
            }
        }
    }
}
