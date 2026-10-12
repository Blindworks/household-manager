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
 * <p>Duplikat- und Limitprüfung gelten nur im Vorschlagspfad: direkte Aktionen belegen keinen
 * offenen Vorschlag, und eine Benachrichtigung soll nie am Limit offener Vorschläge scheitern.
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

    public GateResult submit(AgentAction action) {
        validate(action);
        if (policyService.policyFor(action.actionClass()) == ActionPolicy.DIRECT) {
            AgentProposal row = proposalService.executeDirect(action);
            String message = row.getStatus() == ProposalStatus.FAILED
                    ? "Fehlgeschlagen: " + row.getError()
                    : "Ausgeführt: " + action.describe();
            return new GateResult(message, row.getId(), true);
        }
        if (repository.existsByStatusAndTarget(ProposalStatus.PENDING, action.target())) {
            return new GateResult("Für " + action.target()
                    + " ist bereits ein Vorschlag offen — nicht erneut vorschlagen.", null, false);
        }
        if (repository.countByStatus(ProposalStatus.PENDING) >= properties.getMaxOpenProposals()) {
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
