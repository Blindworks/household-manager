package com.household.manager.agent;

import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Einzige menschenlesbare Darstellung einer {@link AgentAction}: Anzeigenamen aus den Live-Diensten
 * statt roher Entity-IDs ("Nachtmodus aus" statt "Modus input_boolean.manual_nachtmodus off").
 *
 * <p>{@link AgentAction#describe()} bleibt die Form fuer das Modell (dort sind die IDs gewollt) und
 * der Rueckfall hier. Der Describer wirft nie: Eine Darstellung ist Kosmetik und darf weder einen
 * Vorschlag, eine Entscheidung noch die Admin-Liste kippen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentActionDescriber {

    private final ThermostatService thermostatService;
    private final SwitchQueryService switchQueryService;
    private final HouseModeQueryService houseModeQueryService;
    private final NukiLockService nukiLockService;

    /** Text fuer Menschen (Telegram, Admin-Liste, Info-Nachrichten, Vorschlagshistorie). */
    public String describe(AgentAction action) {
        return render(action, false);
    }

    /**
     * Wie {@link #describe}, aber ohne freien Modelltext: Notizen tragen Nutzer-Autoritaet, deshalb
     * liefert eine Nachricht hier die Konstante aus {@link AgentAction#describeForNote()}.
     */
    public String describeForNote(AgentAction action) {
        return render(action, true);
    }

    private String render(AgentAction action, boolean forNote) {
        if (action.actionClass() == ActionClass.NOTIFY) {
            return forNote ? action.describeForNote() : notifyText(action);
        }
        try {
            return resolve(action).orElseGet(action::describe);
        } catch (Exception ex) {
            log.debug("Anzeigename fuer {} nicht aufloesbar: {}", action.describe(), ex.getMessage());
            return action.describe();
        }
    }

    private Optional<String> resolve(AgentAction action) {
        return switch (action.actionClass()) {
            case HEATING -> heating(action);
            case SWITCH -> switchQueryService.listSwitches(null).stream()
                    .filter(s -> action.target().equals(s.entityId())).findFirst()
                    .map(SwitchResponse::displayName).map(n -> withState(n, action));
            case MODE -> houseModeQueryService.listModes().stream()
                    .filter(m -> action.target().equals(m.entityId())).findFirst()
                    .map(ModeResponse::displayName).map(n -> withState(n, action));
            case LOCK -> nukiLockService.listLocks().stream()
                    .filter(l -> action.target().equals(String.valueOf(l.smartlockId()))).findFirst()
                    .map(NukiLockResponse::name).map(n -> n + " verriegeln");
            case NOTIFY -> Optional.empty();
        };
    }

    private Optional<String> heating(AgentAction action) {
        return thermostatService.list().stream()
                .filter(t -> t.id() != null && action.target().equals(String.valueOf(t.id()))).findFirst()
                .map(ThermostatDto::name)
                .map(name -> "Heizung " + name + temperature(action) + mode(action));
    }

    private static String temperature(AgentAction action) {
        Double target = action.doubleParam("targetTemp");
        return target == null ? "" : String.format(Locale.GERMAN, " auf %.1f °C", target);
    }

    private static String mode(AgentAction action) {
        String mode = action.stringParam("mode");
        return mode == null ? "" : " (Modus " + mode + ")";
    }

    private static String withState(String name, AgentAction action) {
        String state = action.stringParam("state");
        if ("on".equals(state)) return name + " an";
        if ("off".equals(state)) return name + " aus";
        return name + " " + state;
    }

    private static String notifyText(AgentAction action) {
        String channel = switch (String.valueOf(action.target())) {
            case "telegram" -> "Telegram";
            case "push" -> "Push";
            default -> action.target();
        };
        return "Nachricht an " + channel + ": " + action.stringParam("message");
    }
}
