package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.repository.AgentProposalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                .reason(abbreviate(action.reason(), ERROR_MAX))
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
     * Admin-Seite und der nächste Prompt sehen, was der Agent getan hat. Eine Info-Nachricht geht nur
     * bei echter Ausführung raus — "war schon so" ist keine Nachricht wert —, ein Fehlschlag wird
     * dagegen immer gemeldet, sonst bliebe er unbemerkt.
     */
    public AgentProposal executeDirect(AgentAction action) {
        LocalDateTime now = LocalDateTime.now(clock);
        AgentProposal p = AgentProposal.builder()
                .actionClass(action.actionClass())
                .target(action.target())
                .actionJson(AgentActionJson.write(objectMapper, action))
                .reason(abbreviate(action.reason(), ERROR_MAX))
                .urgent(action.urgent())
                .decidedBy(SYSTEM_ACTOR)
                .decidedAt(now)
                .createdAt(now)
                .build();
        String info;
        try {
            ActionOutcome outcome = executor.execute(action);
            p.setStatus(ProposalStatus.EXECUTED);
            info = outcome.alreadySatisfied() ? null : "Hausverstand: " + action.describe() + " — " + action.reason();
        } catch (Exception ex) {
            p.setStatus(ProposalStatus.FAILED);
            p.setError(abbreviate(ex.getMessage(), ERROR_MAX));
            info = "Hausverstand: " + action.describe() + " fehlgeschlagen: " + ex.getMessage();
        }
        AgentProposal saved = repository.save(p);
        if (info != null) {
            notifier.sendInfo(info);
        }
        return saved;
    }

    /**
     * Entscheidet einen offenen Vorschlag. Nur {@code PENDING} ist entscheidbar — ein zweiter Knopfdruck
     * (anderer Chat, Admin-Seite) darf die Aktion nicht ein zweites Mal auslösen. Eine Ablehnung wird
     * 24 h als Beobachtung gemerkt, damit der Agent nicht im nächsten Lauf dasselbe wieder fragt.
     */
    @Transactional
    public AgentProposal decide(long id, ProposalDecision decision, String decidedBy) {
        AgentProposal p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Vorschlag: " + id));
        if (p.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException("Vorschlag #" + id + " ist bereits entschieden (" + p.getStatus() + ").");
        }
        AgentAction action = AgentActionJson.read(objectMapper, p.getActionJson());
        LocalDateTime now = LocalDateTime.now(clock);
        String actor = abbreviate(decidedBy == null || decidedBy.isBlank() ? "unbekannt" : decidedBy, DECIDED_BY_MAX);
        p.setDecidedBy(actor);
        p.setDecidedAt(now);
        String label;
        if (decision == ProposalDecision.REJECT) {
            p.setStatus(ProposalStatus.REJECTED);
            rememberQuietly(NoteKind.OBSERVATION, actor + " hat abgelehnt: " + action.describe(),
                    now.plusHours(REJECTION_MEMORY_HOURS));
            label = "Abgelehnt von " + actor;
        } else {
            p.setStatus(ProposalStatus.APPROVED);
            if (decision == ProposalDecision.APPROVE_AND_REMEMBER) {
                rememberQuietly(NoteKind.PREFERENCE,
                        actor + " stimmt zu: " + action.describe() + " (" + p.getReason() + ")", null);
            }
            label = runAction(p, action, "Ausgeführt von " + actor);
        }
        repository.save(p);
        auditService.record("agent.proposal.decide", "#" + id + " " + decision + " durch " + actor);
        notifier.markDecided(p, label);
        return p;
    }

    /** Unbeantwortete Vorschläge verfallen: eine Stunden alte Frage passt meist nicht mehr zur Lage. */
    public int expireStale() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime cutoff = now.minusHours(properties.getProposalTtlHours());
            int expired = 0;
            for (AgentProposal p : repository.findByStatus(ProposalStatus.PENDING)) {
                if (p.getCreatedAt() == null || !p.getCreatedAt().isBefore(cutoff)) {
                    continue;
                }
                try {
                    p.setStatus(ProposalStatus.EXPIRED);
                    p.setDecidedBy(SYSTEM_ACTOR);
                    p.setDecidedAt(now);
                    repository.save(p);
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
                    if (executor.isSatisfied(actionOf(p))) {
                        p.setStatus(ProposalStatus.EXECUTED);
                        p.setDecidedBy(SYSTEM_ACTOR);
                        p.setDecidedAt(now);
                        p.setError(null);
                        repository.save(p);
                    } else {
                        p.setDeliveredAt(now);
                        repository.save(p);
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
            p.setError(abbreviate(ex.getMessage(), ERROR_MAX));
            return "Fehlgeschlagen: " + ex.getMessage();
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

    private static String abbreviate(String text, int max) {
        String value = String.valueOf(text);
        return value.length() <= max ? value : value.substring(0, max);
    }
}
