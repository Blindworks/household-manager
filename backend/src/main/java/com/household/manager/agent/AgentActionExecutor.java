package com.household.manager.agent;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.push.PushNotificationService;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.telegram.TelegramNotificationService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Die einzige Schleuse, ueber die der Hausverstand Geraete anfasst. Der Zustand wird vor
 * jeder Aktion frisch gelesen und nur geschaltet, wenn das Ziel noch nicht erreicht ist
 * (Regel aus {@code confirmToggle}): die Toggle-Endpunkte kippen sonst in die falsche Richtung.
 *
 * <p>{@link #execute} und {@link #isSatisfied} teilen sich je Aktionsklasse genau eine
 * Zustandspruefung, damit "ist schon erreicht" nicht in zwei Varianten auseinanderlaeuft.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentActionExecutor {

    static final double TOLERANCE = 0.01;
    static final String PUSH_TITLE = "Hausverstand";

    private final ThermostatService thermostatService;
    private final SwitchQueryService switchQueryService;
    private final SwitchCommandService switchCommandService;
    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;
    private final NukiLockService nukiLockService;
    private final TelegramNotificationService telegramNotificationService;
    private final PushNotificationService pushNotificationService;
    private final AuditService auditService;

    /** Fuehrt die Aktion aus, sofern das Ziel nicht schon erreicht ist; wirft bei Geraetefehlern. */
    public ActionOutcome execute(AgentAction action) {
        ActionOutcome outcome = switch (action.actionClass()) {
            case HEATING -> heating(action);
            case SWITCH -> toggleEntity(action, switchState(action.target()),
                    () -> switchCommandService.toggle(action.target()));
            case MODE -> toggleEntity(action, modeState(action.target()),
                    () -> manualEntityService.toggle(action.target()));
            case NOTIFY -> notify(action);
            case LOCK -> lock(action);
        };
        if (!outcome.alreadySatisfied()) {
            auditService.record(AuditActor.agent(), "agent.action.execute",
                    action.describe() + " — " + action.reason());
        }
        return outcome;
    }

    /**
     * Wie {@link #execute}, aber nur lesend: kein Geraeteaufruf, kein Audit. Wahr genau dann,
     * wenn der Zielzustand schon erreicht ist; eine Nachricht ist nie "erreicht".
     */
    public boolean isSatisfied(AgentAction action) {
        return switch (action.actionClass()) {
            case HEATING -> heatingSatisfied(action, currentThermostat(action));
            case SWITCH -> stateSatisfied(action, switchState(action.target()));
            case MODE -> stateSatisfied(action, modeState(action.target()));
            case NOTIFY -> false;
            case LOCK -> lockSatisfied(currentLock(action));
        };
    }

    // --- HEATING ---

    private ThermostatDto currentThermostat(AgentAction action) {
        long id = Long.parseLong(action.target());
        return thermostatService.list().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Thermostat: " + id));
    }

    private boolean targetSatisfied(AgentAction action, ThermostatDto current) {
        Double target = action.doubleParam("targetTemp");
        return target == null || (current.targetTemp() != null && Math.abs(current.targetTemp() - target) < TOLERANCE);
    }

    private boolean modeSatisfied(AgentAction action, ThermostatDto current) {
        String mode = action.stringParam("mode");
        return mode == null || current.mode() == ThermostatMode.valueOf(mode);
    }

    private boolean heatingSatisfied(AgentAction action, ThermostatDto current) {
        if (action.doubleParam("targetTemp") == null && action.stringParam("mode") == null) {
            throw new IllegalArgumentException("targetTemp oder mode angeben");
        }
        return targetSatisfied(action, current) && modeSatisfied(action, current);
    }

    private ActionOutcome heating(AgentAction action) {
        ThermostatDto current = currentThermostat(action);
        boolean targetOk = targetSatisfied(action, current);
        boolean modeOk = modeSatisfied(action, current);
        if (heatingSatisfied(action, current)) {
            return new ActionOutcome(true, current.name() + " steht bereits so.");
        }
        if (!modeOk) {
            thermostatService.setMode(current.id(), ThermostatMode.valueOf(action.stringParam("mode")));
        }
        if (!targetOk) {
            thermostatService.setTarget(current.id(), action.doubleParam("targetTemp"));
        }
        return new ActionOutcome(false, current.name() + ": " + action.describe());
    }

    // --- SWITCH / MODE ---

    private String switchState(String entityId) {
        return switchQueryService.listSwitches(null).stream()
                .filter(s -> s.entityId().equals(entityId)).findFirst().map(SwitchResponse::state)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Schalter: " + entityId));
    }

    private String modeState(String entityId) {
        return houseModeQueryService.listModes().stream()
                .filter(m -> m.entityId().equals(entityId)).findFirst().map(ModeResponse::state)
                .orElseThrow(() -> new IllegalArgumentException("Kein Haus-Modus: " + entityId));
    }

    private String desiredState(AgentAction action) {
        String desired = action.stringParam("state");
        if (!"on".equals(desired) && !"off".equals(desired)) {
            throw new IllegalArgumentException("state muss on oder off sein");
        }
        return desired;
    }

    /**
     * Die Toggle-Endpunkte waehlen die Richtung aus dem Ist-Zustand und schalten bei allem ausser "on"
     * EIN. Bei unavailable/unknown wuerde "off" also einschalten; deshalb wird ein anderer Ist-Zustand
     * als on/off hier fuer execute und isSatisfied gleichermassen abgelehnt.
     */
    private boolean stateSatisfied(AgentAction action, String currentState) {
        String desired = desiredState(action);
        if (!"on".equals(currentState) && !"off".equals(currentState)) {
            throw new IllegalArgumentException("Zustand von " + action.target() + " ist " + currentState + " — nicht schaltbar");
        }
        return desired.equals(currentState);
    }

    private ActionOutcome toggleEntity(AgentAction action, String currentState, Runnable toggle) {
        if (stateSatisfied(action, currentState)) {
            return new ActionOutcome(true, action.target() + " ist bereits " + desiredState(action) + ".");
        }
        toggle.run();
        return new ActionOutcome(false, action.target() + " ist jetzt " + desiredState(action) + ".");
    }

    // --- LOCK ---

    private NukiLockResponse currentLock(AgentAction action) {
        long smartlockId = Long.parseLong(action.target());
        return nukiLockService.listLocks().stream().filter(l -> l.smartlockId() == smartlockId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Schloss: " + smartlockId));
    }

    private boolean lockSatisfied(NukiLockResponse lock) {
        return "locked".equalsIgnoreCase(lock.state());
    }

    private ActionOutcome lock(AgentAction action) {
        NukiLockResponse lock = currentLock(action);
        if (lockSatisfied(lock)) {
            return new ActionOutcome(true, lock.name() + " ist bereits verriegelt.");
        }
        // SICHERHEITSANKER: der Agent darf ausschliesslich verriegeln. Entriegeln oder die Tuer
        // oeffnen gibt es in dieser Klasse bewusst nicht (Code-Garantie, kein Prompt-Schutz).
        nukiLockService.executeAction(lock.smartlockId(), NukiLockAction.LOCK);
        return new ActionOutcome(false, lock.name() + " wird verriegelt.");
    }

    // --- NOTIFY ---

    /**
     * Nur Telegram und Push. Alexa ist bewusst kein Kanal: {@code AlexaAnnouncementService.announce}
     * verlangt eine Geräteliste, die der Hausverstand nicht kennt — mit leerer Liste scheiterte jede
     * Ansage. Erst mit einer konfigurierbaren Zielgeräte-Liste wieder aufnehmen.
     */
    private ActionOutcome notify(AgentAction action) {
        String message = action.stringParam("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message fehlt");
        }
        switch (String.valueOf(action.stringParam("channel"))) {
            case "telegram" -> telegramNotificationService.sendToAllowedChats(message);
            case "push" -> pushNotificationService.sendToAll(PUSH_TITLE, message);
            default -> throw new IllegalArgumentException("channel muss telegram oder push sein");
        }
        return new ActionOutcome(false, "Nachricht gesendet.");
    }
}
