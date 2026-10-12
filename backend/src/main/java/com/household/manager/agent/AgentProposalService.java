package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.repository.AgentProposalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Lebenszyklus eines Vorschlags: anlegen und zustellen, entscheiden, verfallen lassen, in der
 * Ruhezeit zurückgehaltene später nachreichen. Die Leitplanken (Höchstzahl offener Vorschläge,
 * kein Doppelvorschlag je Ziel, NOTIFY direkt) prüft bewusst NICHT diese Klasse, sondern die
 * vorgelagerte Schleuse — hier geht es nur um Persistenz und Zustellung.
 *
 * <p>Die Methoden der Agentenschleife ({@link #expireStale}, {@link #deliverPending}) werfen nie:
 * ein kaputter Vorschlag darf den Lauf des Hausverstands nicht abbrechen. {@link #decide},
 * {@link #propose} und {@link #executeDirect} dürfen bei Aufruferfehlern werfen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentProposalService {

    static final String CALLBACK_PREFIX = "agent";
    static final String SYSTEM_ACTOR = "system";
    static final int DECIDED_BY_MAX = 100;
    static final int ERROR_MAX = 1000;
    static final int REJECTION_MEMORY_HOURS = 24;
    static final int PROMPT_LOOKBACK_HOURS = 24;
    static final String NO_REASON = "(keine Begründung)";

    private final AgentProposalRepository repository;
    private final AgentActionExecutor executor;
    private final AgentNoteService noteService;
    private final AgentNotifier notifier;
    private final AgentQuietHours quietHours;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Clock clock;

    /** Inhalt eines Telegram-Knopfs: welcher Vorschlag, welche Antwort. */
    public record ParsedCallback(long proposalId, ProposalDecision decision) {
    }

    /** Einzige Bildung der Callback-Daten; {@link #parseCallback} ist ihr Gegenstück. */
    public static String callbackData(long proposalId, ProposalDecision decision) {
        return CALLBACK_PREFIX + ":" + proposalId + ":" + decision.name();
    }

    /**
     * Callback-Daten kommen von außen (jeder, der dem Bot schreiben kann, kann beliebige Daten senden),
     * deshalb führt jede Abweichung vom Format zu "leer" statt zu einer Exception.
     */
    public static Optional<ParsedCallback> parseCallback(String data) {
        if (data == null) {
            return Optional.empty();
        }
        String[] parts = data.split(":", -1);
        if (parts.length != 3 || !CALLBACK_PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParsedCallback(Long.parseLong(parts[1]), ProposalDecision.valueOf(parts[2])));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Legt einen offenen Vorschlag an. In der Ruhezeit wird er nur gespeichert und von
     * {@link #deliverPending} nach ihrem Ende nachgereicht — außer er ist {@code urgent}: ein
     * Frostschutz-Fall soll nicht bis morgens warten.
     */
    public AgentProposal propose(AgentAction action) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean deliverNow = action.urgent() || !quietHours.isQuietNow();
        AgentProposal p = repository.save(AgentProposal.builder()
                .actionClass(action.actionClass())
                .target(action.target())
                .actionJson(AgentActionJson.write(objectMapper, action))
                .reason(abbreviate(reasonOf(action), ERROR_MAX))
                .urgent(action.urgent())
                .status(ProposalStatus.PENDING)
                .createdAt(now)
                .deliveredAt(deliverNow ? now : null)
                .build());
        if (deliverNow) {
            notifier.sendProposal(p);
        }
        return p;
    }

    /**
     * Führt eine freigabefreie Aktion sofort aus und protokolliert sie trotzdem als Zeile, damit die
     * Admin-Seite und der nächste Prompt sehen, was der Agent getan hat.
     *
     * <p>Info-Nachrichten: nur bei echter Ausführung ("war schon so" ist keine Nachricht wert), ein
     * Fehlschlag dagegen immer, sonst bliebe er unbemerkt. Bei NOTIFY gibt es keine Erfolgs-Info — die
     * Nachricht IST die Aktion, eine zweite käme doppelt an. In der Ruhezeit geht keine Info-Nachricht
     * raus (Zeile und Audit genügen), außer die Aktion ist {@code urgent}.
     *
     * <p>Eine nicht dringende NOTIFY in der Ruhezeit wird gar nicht ausgeführt, sondern als PENDING ohne
     * {@code deliveredAt} gespeichert; {@link #deliverPending} sendet sie nach dem Ende der Ruhezeit.
     */
    public AgentProposal executeDirect(AgentAction action) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean quiet = !action.urgent() && quietHours.isQuietNow();
        AgentProposal.AgentProposalBuilder row = AgentProposal.builder()
                .actionClass(action.actionClass())
                .target(action.target())
                .actionJson(AgentActionJson.write(objectMapper, action))
                .reason(abbreviate(reasonOf(action), ERROR_MAX))
                .urgent(action.urgent())
                .createdAt(now);
        if (quiet && action.actionClass() == ActionClass.NOTIFY) {
            return repository.save(row.status(ProposalStatus.PENDING).deliveredAt(null).build());
        }
        AgentProposal p = row.decidedBy(SYSTEM_ACTOR).decidedAt(now).build();
        String info;
        try {
            ActionOutcome outcome = executor.execute(action);
            p.setStatus(ProposalStatus.EXECUTED);
            info = outcome.alreadySatisfied() || action.actionClass() == ActionClass.NOTIFY
                    ? null
                    : "Hausverstand: " + action.describe() + " — " + reasonOf(action);
        } catch (Exception ex) {
            p.setStatus(ProposalStatus.FAILED);
            p.setError(abbreviate(messageOf(ex), ERROR_MAX));
            info = "Hausverstand: " + action.describe() + " fehlgeschlagen: " + messageOf(ex);
        }
        AgentProposal saved = repository.save(p);
        if (info != null && !quiet) {
            notifier.sendInfo(info);
        }
        return saved;
    }

    /**
     * Entscheidet einen offenen Vorschlag. Nur {@code PENDING} ist entscheidbar — ein zweiter Knopfdruck
     * (anderer Chat, Admin-Seite) darf die Aktion nicht ein zweites Mal auslösen. Eine Ablehnung wird
     * 24 h als Beobachtung gemerkt, damit der Agent nicht im nächsten Lauf dasselbe wieder fragt.
     *
     * <p>Bewusst NICHT {@code @Transactional}: der Vorschlag wird zuerst per {@link AgentProposalRepository#claim}
     * atomar von PENDING weg geschaltet (Schutz gegen den Doppelklick — der zweite Aufrufer verliert), erst
     * danach läuft die Aktion außerhalb jeder Transaktion. Liefe sie in einer, könnte eine Exception eines
     * transaktionalen Geräte-Services die umgebende Transaktion auf rollback-only setzen, und der Status
     * {@code FAILED} ginge beim Commit verloren. {@code markDecided} kommt zuletzt, nach dem Speichern des
     * Endstands, damit die Knöpfe nie verschwinden, ohne dass die Entscheidung gespeichert ist.
     */
    public AgentProposal decide(long id, ProposalDecision decision, String decidedBy) {
        AgentProposal p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Vorschlag: " + id));
        if (p.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException("Vorschlag #" + id + " ist bereits entschieden (" + p.getStatus() + ").");
        }
        AgentAction action = AgentActionJson.read(objectMapper, p.getActionJson());
        LocalDateTime now = LocalDateTime.now(clock);
        String actor = abbreviate(decidedBy == null || decidedBy.isBlank() ? "unbekannt" : decidedBy, DECIDED_BY_MAX);
        ProposalStatus claimed = decision == ProposalDecision.REJECT ? ProposalStatus.REJECTED : ProposalStatus.APPROVED;
        if (repository.claim(id, ProposalStatus.PENDING, claimed, actor, now) == 0) {
            throw new IllegalStateException("Vorschlag #" + id + " ist bereits entschieden.");
        }
        p.setStatus(claimed);
        p.setDecidedBy(actor);
        p.setDecidedAt(now);
        String label;
        if (decision == ProposalDecision.REJECT) {
            rememberQuietly(NoteKind.OBSERVATION, actor + " hat abgelehnt: " + action.describe(),
                    now.plusHours(REJECTION_MEMORY_HOURS));
            label = "Abgelehnt von " + actor;
        } else {
            if (decision == ProposalDecision.APPROVE_AND_REMEMBER) {
                // Bewusst ohne die Begruendung des Modells: die Notiz traegt Nutzer-Autoritaet und ist fuer den
                // Agenten unveraenderbar — freier Modelltext darin waere eine Selbstanweisung mit Nutzerrang.
                rememberQuietly(NoteKind.PREFERENCE, actor + " stimmt zu: " + action.describe(), null);
            }
            label = runAction(p, action, "Ausgeführt von " + actor);
        }
        repository.save(p);
        auditService.record("agent.proposal.decide", "#" + id + " " + decision + " durch " + actor);
        notifier.markDecided(p, label);
        return p;
    }

    /**
     * Unbeantwortete Vorschläge verfallen: eine Stunden alte Frage passt meist nicht mehr zur Lage. Die
     * Frist läuft ab der Zustellung, nicht ab dem Anlegen — ein um 23:00 in der Ruhezeit zurückgehaltener
     * Vorschlag verfiele sonst um 01:00 und käme morgens nie an. Nicht zugestellte Zeilen (auch
     * zurückgehaltene Nachrichten) bleiben deshalb hier unberührt; sie stellt {@link #deliverPending} zu.
     */
    public int expireStale() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime cutoff = now.minusHours(properties.getProposalTtlHours());
            int expired = 0;
            for (AgentProposal p : repository.findByStatus(ProposalStatus.PENDING)) {
                if (p.getDeliveredAt() == null || !p.getDeliveredAt().isBefore(cutoff)) {
                    continue;
                }
                try {
                    // Per Claim statt save(): ein parallel entschiedener Vorschlag wuerde sonst ueberschrieben.
                    if (repository.claim(p.getId(), ProposalStatus.PENDING, ProposalStatus.EXPIRED, SYSTEM_ACTOR, now) == 0) {
                        continue;
                    }
                    p.setStatus(ProposalStatus.EXPIRED);
                    p.setDecidedBy(SYSTEM_ACTOR);
                    p.setDecidedAt(now);
                    notifier.markDecided(p, "Verfallen (unbeantwortet)");
                    expired++;
                } catch (Exception ex) {
                    log.warn("Vorschlag {} konnte nicht verfallen: {}", p.getId(), ex.getMessage());
                }
            }
            return expired;
        } catch (Exception ex) {
            log.warn("Verfall der Vorschläge fehlgeschlagen: {}", ex.getMessage());
            return 0;
        }
    }

    /**
     * Reicht in der Ruhezeit zurückgehaltene Vorschläge nach. Vorher wird frisch geprüft, ob das Ziel
     * inzwischen erreicht ist (jemand hat die Heizung schon selbst hochgedreht) — dann wäre die Frage
     * am Morgen nur Lärm, der Vorschlag gilt als erledigt. Scheitert die Prüfung, bleibt er liegen und
     * wird im nächsten Lauf erneut versucht (oder verfällt).
     *
     * <p>Kein {@code save()} der geladenen Zeile: {@code isSatisfied} kann Sekunden dauern, und wer in
     * dieser Zeit auf der Admin-Seite entscheidet, dessen Status würde mit PENDING überschrieben — samt
     * frischer Knöpfe, über die die Aktion ein zweites Mal liefe. Beide Übergänge laufen deshalb als
     * bedingte UPDATEs, die nur auf einer noch offenen Zeile greifen.
     *
     * <p>Zurückgehaltene Nachrichten (NOTIFY) sind keine Frage, sie werden ohne Knöpfe gesendet:
     * erst per Claim auf EXECUTED beansprucht, dann gesendet (höchstens einmal, auch bei zwei parallelen
     * Läufen). Scheitert der Versand, wird die schon beanspruchte Zeile auf FAILED samt Fehlertext gespeichert —
     * ohne Wettlauf mit {@code decide}, dessen Claim auf PENDING dann ins Leere geht.
     */
    public int deliverPending() {
        try {
            if (quietHours.isQuietNow()) {
                return 0;
            }
            int handled = 0;
            for (AgentProposal p : repository.findByStatus(ProposalStatus.PENDING)) {
                if (p.getDeliveredAt() != null) {
                    continue;
                }
                try {
                    LocalDateTime now = LocalDateTime.now(clock);
                    if (p.getActionClass() == ActionClass.NOTIFY) {
                        if (deliverHeldNotification(p, now)) {
                            handled++;
                        }
                        continue;
                    }
                    if (executor.isSatisfied(actionOf(p))) {
                        if (repository.claim(p.getId(), ProposalStatus.PENDING, ProposalStatus.EXECUTED,
                                SYSTEM_ACTOR, now) == 0) {
                            continue;
                        }
                        p.setStatus(ProposalStatus.EXECUTED);
                        p.setDecidedBy(SYSTEM_ACTOR);
                        p.setDecidedAt(now);
                    } else {
                        if (repository.markDelivered(p.getId(), ProposalStatus.PENDING, now) == 0) {
                            continue;
                        }
                        p.setDeliveredAt(now);
                        notifier.sendProposal(p);
                    }
                    handled++;
                } catch (Exception ex) {
                    log.warn("Zurückgehaltener Vorschlag {} nicht zustellbar: {}", p.getId(), ex.getMessage());
                }
            }
            return handled;
        } catch (Exception ex) {
            log.warn("Nachreichen der Vorschläge fehlgeschlagen: {}", ex.getMessage());
            return 0;
        }
    }

    private boolean deliverHeldNotification(AgentProposal p, LocalDateTime now) {
        if (repository.claim(p.getId(), ProposalStatus.PENDING, ProposalStatus.EXECUTED, SYSTEM_ACTOR, now) == 0) {
            return false;
        }
        p.setStatus(ProposalStatus.EXECUTED);
        p.setDecidedBy(SYSTEM_ACTOR);
        p.setDecidedAt(now);
        try {
            executor.execute(actionOf(p));
        } catch (Exception ex) {
            log.warn("Zurückgehaltene Nachricht {} nicht zustellbar: {}", p.getId(), messageOf(ex));
            p.setStatus(ProposalStatus.FAILED);
            p.setError(abbreviate(messageOf(ex), ERROR_MAX));
            repository.save(p);
        }
        return true;
    }

    public long countPending() {
        return repository.countByStatus(ProposalStatus.PENDING);
    }

    public List<AgentProposal> listRecent() {
        return repository.findTop50ByOrderByCreatedAtDesc();
    }

    /** Alle Status der letzten 24 h — auch Ablehnungen und Fehlschläge, damit der Agent daraus lernt. */
    public List<AgentProposal> recentForPrompt() {
        return repository.findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                EnumSet.allOf(ProposalStatus.class), LocalDateTime.now(clock).minusHours(PROMPT_LOOKBACK_HOURS));
    }

    public AgentAction actionOf(AgentProposal p) {
        return AgentActionJson.read(objectMapper, p.getActionJson());
    }

    private String runAction(AgentProposal p, AgentAction action, String successLabel) {
        try {
            ActionOutcome outcome = executor.execute(action);
            p.setStatus(ProposalStatus.EXECUTED);
            return outcome.alreadySatisfied() ? "War bereits so" : successLabel;
        } catch (Exception ex) {
            p.setStatus(ProposalStatus.FAILED);
            p.setError(abbreviate(messageOf(ex), ERROR_MAX));
            return "Fehlgeschlagen: " + messageOf(ex);
        }
    }

    /**
     * Eine volle Notizliste (Notizgrenze) darf die Entscheidung selbst nicht verhindern —
     * die Zustimmung des Nutzers wiegt schwerer als das Gedächtnis des Agenten.
     */
    private void rememberQuietly(NoteKind kind, String text, LocalDateTime expiresAt) {
        try {
            noteService.create(kind, abbreviate(text, AgentNoteService.MAX_TEXT_LENGTH), NoteSource.USER, expiresAt);
        } catch (Exception ex) {
            log.warn("Notiz zur Entscheidung nicht gespeichert: {}", ex.getMessage());
        }
    }

    /** Eine fehlende Begruendung soll in Zeile und Nachricht nicht als Wort "null" erscheinen. */
    private static String reasonOf(AgentAction action) {
        String reason = action.reason();
        return reason == null || reason.isBlank() ? NO_REASON : reason;
    }

    /** Manche Exceptions (z. B. NPE) tragen keine Nachricht — dann wenigstens den Typ nennen. */
    private static String messageOf(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static String abbreviate(String text, int max) {
        String value = String.valueOf(text);
        return value.length() <= max ? value : value.substring(0, max);
    }
}
