package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentNote;
import com.household.manager.repository.AgentNoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gedaechtnis des Hausverstands: Beobachtungen (befristet) und Vorlieben (unbefristet).
 * Alle Regeln — Textlaenge, Obergrenze, Loeschschutz fuer Nutzernotizen — stehen hier und
 * nicht in den Tools, damit auch spaetere Aufrufer (UI, API) sie nicht umgehen koennen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentNoteService {

    static final int MAX_TEXT_LENGTH = 500;
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentNoteRepository repository;
    private final AgentProperties properties;
    private final AuditService auditService;
    private final Clock clock;

    /** Abgelaufene Notizen zaehlen nicht mehr, auch wenn der Aufraeumjob sie noch nicht geloescht hat. */
    public List<AgentNote> listActive() {
        LocalDateTime now = LocalDateTime.now(clock);
        return repository.findAllByOrderByCreatedAtAsc().stream()
                .filter(n -> n.getExpiresAt() == null || n.getExpiresAt().isAfter(now))
                .toList();
    }

    public AgentNote create(NoteKind kind, String text, NoteSource source, LocalDateTime expiresAt) {
        String cleaned = validatedText(text);
        if (listActive().size() >= properties.getMaxActiveNotes()) {
            throw new IllegalStateException("Notizgrenze erreicht (" + properties.getMaxActiveNotes()
                    + " aktive Notizen) — erst eine Notiz loeschen oder aktualisieren.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AgentNote saved = repository.save(AgentNote.builder()
                .kind(kind).text(cleaned).source(source)
                .createdAt(now).updatedAt(now).expiresAt(expiresAt)
                .build());
        auditService.record("agent.note.create", kind + "/" + source + ": " + cleaned);
        return saved;
    }

    /** Art und Herkunft bleiben bewusst erhalten: eine Nutzer-Vorliebe wird durch Umformulieren nicht zur Agentennotiz. */
    public AgentNote update(long id, String text, LocalDateTime expiresAt, NoteSource requester) {
        String cleaned = validatedText(text);
        AgentNote note = find(id);
        // Sonst liesse sich Loeschschutz umgehen: Umschreiben oder Ablauf setzen entfernt die Notiz faktisch.
        if (requester == NoteSource.AGENT && note.getSource() == NoteSource.USER) {
            throw new IllegalArgumentException("Notiz #" + id + " stammt vom Nutzer und darf nicht vom Agenten geaendert werden.");
        }
        note.setText(cleaned);
        note.setExpiresAt(expiresAt);
        note.setUpdatedAt(LocalDateTime.now(clock));
        AgentNote saved = repository.save(note);
        auditService.record("agent.note.update", "#" + id + ": " + cleaned);
        return saved;
    }

    public void delete(long id, NoteSource requester) {
        AgentNote note = find(id);
        if (requester == NoteSource.AGENT && note.getSource() == NoteSource.USER) {
            throw new IllegalArgumentException("Notiz #" + id + " stammt vom Nutzer und darf nicht vom Agenten geloescht werden.");
        }
        repository.deleteById(id);
        auditService.record("agent.note.delete", "#" + id + " durch " + requester);
    }

    /** Wirft nie: der Aufraeumjob darf den Scheduler nicht kippen. */
    public int purgeExpired() {
        try {
            return repository.deleteExpired(LocalDateTime.now(clock));
        } catch (Exception ex) {
            log.warn("Abgelaufene Agent-Notizen konnten nicht geloescht werden: {}", ex.getMessage());
            return 0;
        }
    }

    /** Eine Zeile je Notiz; die Id steht vorn, damit das Modell sie fuer update/delete zitieren kann. */
    public String renderForPrompt() {
        List<AgentNote> notes = listActive();
        if (notes.isEmpty()) {
            return "(keine Notizen)";
        }
        return notes.stream()
                .map(n -> "#" + n.getId() + " [" + n.getKind() + ", " + n.getSource() + ", "
                        + (n.getExpiresAt() == null ? "unbefristet" : "bis " + EXPIRY_FORMAT.format(n.getExpiresAt()))
                        + "] " + n.getText())
                .collect(Collectors.joining("\n"));
    }

    private AgentNote find(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Keine Notiz mit der Id " + id));
    }

    private static String validatedText(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Der Notiztext darf nicht leer sein.");
        }
        if (cleaned.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Der Notiztext ist laenger als " + MAX_TEXT_LENGTH + " Zeichen.");
        }
        return cleaned;
    }
}
