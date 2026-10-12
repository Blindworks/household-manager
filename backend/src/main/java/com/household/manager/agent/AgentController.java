package com.household.manager.agent;

import com.household.manager.agent.AgentDtos.DecisionRequest;
import com.household.manager.agent.AgentDtos.NoteDto;
import com.household.manager.agent.AgentDtos.NoteRequest;
import com.household.manager.agent.AgentDtos.PolicyDto;
import com.household.manager.agent.AgentDtos.PolicyUpdateRequest;
import com.household.manager.agent.AgentDtos.ProposalDto;
import com.household.manager.agent.AgentDtos.RunDto;
import com.household.manager.agent.AgentDtos.RunsResponse;
import com.household.manager.agent.AgentDtos.SettingsDto;
import com.household.manager.agent.AgentDtos.SummaryResponse;
import com.household.manager.model.entity.AgentNote;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.model.entity.AgentRun;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.CurrentUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * REST-Schnittstelle des Hausverstands. Bewusst dünn: Fachregeln und Audit liegen in den Services.
 *
 * <p>Alles unter {@code /v1/agent} ist ADMIN (Notizen enthalten Gewohnheiten des Haushalts), nur
 * {@code GET /summary} ist für das Wandtablet freigegeben (siehe {@code SecurityConfig}). Fehler laufen
 * über den {@code GlobalExceptionHandler} (IllegalArgument/IllegalState → 400, zu häufiger Lauf → 429),
 * nie 401 — der Auth-Interceptor des Frontends würde sonst die Haushalts-Session beenden.
 */
@RestController
@RequestMapping("/v1/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private static final int DEFAULT_PROPOSAL_LIMIT = 50;
    private static final int MAX_PROPOSAL_LIMIT = 200;
    private static final String FALLBACK_DECIDER = "Admin";

    private final AgentSettingsService settingsService;
    private final AgentPolicyService policyService;
    private final AgentNoteService noteService;
    private final AgentProposalService proposalService;
    private final AgentRunRecorder runRecorder;
    private final AgentWakeService wakeService;
    private final AgentNotifier notifier;
    private final CurrentUserService currentUserService;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return new SummaryResponse(proposalService.countPending(), settingsService.isActive(),
                notifier.isConfigured());
    }

    @GetMapping("/settings")
    public SettingsDto settings() {
        return new SettingsDto(settingsService.isActive());
    }

    @PutMapping("/settings")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto request) {
        settingsService.setActive(request.active());
        return new SettingsDto(settingsService.isActive());
    }

    @GetMapping("/policies")
    public List<PolicyDto> policies() {
        return policyService.all().entrySet().stream()
                .map(e -> new PolicyDto(e.getKey(), e.getValue()))
                .toList();
    }

    @PutMapping("/policies/{actionClass}")
    public List<PolicyDto> updatePolicy(@PathVariable ActionClass actionClass,
                                        @Valid @RequestBody PolicyUpdateRequest request) {
        policyService.update(actionClass, request.policy());
        return policies();
    }

    @GetMapping("/notes")
    public List<NoteDto> notes() {
        return noteService.listActive().stream().map(AgentController::toDto).toList();
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto createNote(@Valid @RequestBody NoteRequest request) {
        return toDto(noteService.create(request.kind(), request.text(), NoteSource.USER, request.expiresAt()));
    }

    @PutMapping("/notes/{id}")
    public NoteDto updateNote(@PathVariable long id, @Valid @RequestBody NoteRequest request) {
        return toDto(noteService.update(id, request.text(), request.expiresAt(), NoteSource.USER));
    }

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable long id) {
        noteService.delete(id, NoteSource.USER);
    }

    /** {@code limit} wird nur validiert/geklemmt; {@code listRecent()} liefert ohnehin höchstens 50. */
    @GetMapping("/proposals")
    public List<ProposalDto> proposals(@RequestParam(required = false) Integer limit) {
        int effective = limit == null ? DEFAULT_PROPOSAL_LIMIT : Math.max(1, Math.min(limit, MAX_PROPOSAL_LIMIT));
        return proposalService.listRecent().stream().limit(effective).map(this::toDto).toList();
    }

    @PostMapping("/proposals/{id}/decide")
    public ProposalDto decide(@PathVariable long id, @Valid @RequestBody DecisionRequest request) {
        return toDto(proposalService.decide(id, request.decision(), deciderName()));
    }

    @GetMapping("/runs")
    public RunsResponse runs() {
        long[] tokens = runRecorder.tokensToday();
        return new RunsResponse(runRecorder.recent().stream().map(AgentController::toDto).toList(),
                tokens[0], tokens[1]);
    }

    /** Antwort aus dem frischen Ergebnis statt aus der DB: der Lauf selbst schreibt sein Protokoll im Recorder. */
    @PostMapping("/runs")
    public RunDto runNow() {
        AgentRunResult r = wakeService.runNow();
        return new RunDto(null, AgentTriggerType.MANUAL, "Manuell", LocalDateTime.now(clock), r.durationMs(), null,
                r.inputTokens(), r.outputTokens(), r.toolCalls(), r.summary(), r.error());
    }

    private String deciderName() {
        return currentUserService.currentUserId()
                .flatMap(appUserRepository::findById)
                .map(user -> user.getDisplayName())
                .filter(name -> name != null && !name.isBlank())
                .orElse(FALLBACK_DECIDER);
    }

    private ProposalDto toDto(AgentProposal p) {
        return new ProposalDto(p.getId(), p.getActionClass(), p.getTarget(), describe(p), p.getReason(),
                p.isUrgent(), p.getStatus(), p.getDecidedBy(), p.getDecidedAt(), p.getDeliveredAt(),
                p.getError(), p.getCreatedAt());
    }

    /** Ein unlesbares Aktions-JSON darf die ganze Liste nicht kippen. */
    private String describe(AgentProposal p) {
        try {
            return proposalService.actionOf(p).describe();
        } catch (Exception ex) {
            log.warn("Aktion von Vorschlag {} nicht lesbar: {}", p.getId(), ex.getMessage());
            return p.getActionClass() + " " + p.getTarget();
        }
    }

    private static NoteDto toDto(AgentNote n) {
        return new NoteDto(n.getId(), n.getKind(), n.getText(), n.getSource(), n.getCreatedAt(), n.getExpiresAt());
    }

    private static RunDto toDto(AgentRun r) {
        return new RunDto(r.getId(), r.getTriggerType(), r.getTriggerDetail(), r.getStartedAt(), r.getDurationMs(),
                r.getModel(), r.getInputTokens(), r.getOutputTokens(), r.getToolCalls(), r.getSummary(),
                r.getError());
    }
}
