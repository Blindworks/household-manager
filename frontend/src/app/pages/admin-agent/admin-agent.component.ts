import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AgentService } from '../../services/agent.service';
import {
  ActionClass,
  ActionPolicy,
  AgentNote,
  AgentNoteRequest,
  AgentPolicy,
  AgentProposal,
  AgentRun,
  AgentSummary,
  NoteKind,
  ProposalDecision
} from '../../models/agent.model';
import {
  ACTION_CLASS_LABELS,
  NOTE_KIND_LABELS,
  POLICY_LABELS,
  PROPOSAL_STATUS_LABELS,
  TRIGGER_LABELS,
  sourceLabel
} from '../../shared/agent-labels.util';

const PROPOSAL_LIMIT = 50;
const RESULT_PREVIEW_LENGTH = 120;
const RATE_LIMIT_MESSAGE = 'Bitte warten – der letzte manuelle Lauf ist noch zu frisch.';

/** Formularzustand einer Notiz; `expiresAt` im `datetime-local`-Format (yyyy-MM-ddTHH:mm) oder leer. */
interface NoteForm {
  kind: NoteKind;
  text: string;
  expiresAt: string;
}

function emptyNoteForm(): NoteForm {
  return { kind: 'PREFERENCE', text: '', expiresAt: '' };
}

/**
 * Admin-Seite „Hausverstand": Betrieb, Freigaben, Notizen, Vorschläge und Läufe.
 * Die Abschnitte laden unabhängig voneinander, ein Fehler betrifft nur seinen Abschnitt.
 */
@Component({
  selector: 'app-admin-agent',
  standalone: true,
  imports: [FormsModule, DatePipe, NgClass],
  templateUrl: './admin-agent.component.html',
  styleUrl: './admin-agent.component.scss'
})
export class AdminAgentComponent implements OnInit {
  private readonly api = inject(AgentService);

  readonly actionClassLabels = ACTION_CLASS_LABELS;
  readonly policyLabels = POLICY_LABELS;
  readonly triggerLabels = TRIGGER_LABELS;
  readonly statusLabels = PROPOSAL_STATUS_LABELS;
  readonly noteKindLabels = NOTE_KIND_LABELS;
  readonly sourceLabel = sourceLabel;
  readonly policyHints: Record<ActionClass, string> = {
    HEATING: 'Zieltemperatur 5–24 °C',
    SWITCH: 'Schalter und Licht an/aus',
    MODE: 'Modi und Helfer schalten',
    NOTIFY: 'Telegram und Push, immer direkt',
    LOCK: 'Haustür nur verriegeln'
  };
  readonly segmentLabels: Record<ActionPolicy, string> = { PROPOSE: 'nur vorschlagen', DIRECT: 'direkt ausführen' };
  readonly policyOptions: ActionPolicy[] = ['PROPOSE', 'DIRECT'];
  readonly noteKinds: NoteKind[] = ['OBSERVATION', 'PREFERENCE'];

  readonly summary = signal<AgentSummary | null>(null);
  readonly policies = signal<AgentPolicy[]>([]);
  readonly notes = signal<AgentNote[]>([]);
  readonly proposals = signal<AgentProposal[]>([]);
  readonly runs = signal<AgentRun[]>([]);
  readonly inputTokensToday = signal(0);
  readonly outputTokensToday = signal(0);
  readonly dailyTokenBudget = signal(0);
  readonly budgetExhausted = signal(false);
  readonly tokensUsedToday = computed(() => this.inputTokensToday() + this.outputTokensToday());

  /** Prozentfüllung des Token-Balkens, auf 0..100 begrenzt. */
  readonly gaugePercent = computed(() => {
    const budget = this.dailyTokenBudget();
    return budget > 0 ? Math.min(100, Math.round(this.tokensUsedToday() / budget * 100)) : 0;
  });
  readonly gaugeLevel = computed<'ok' | 'warn' | 'error'>(() =>
    this.budgetExhausted() ? 'error' : this.gaugePercent() >= 70 ? 'warn' : 'ok');
  /** Läufe mit heutigem Berliner Datum; startedAt ist lokale Haushaltszeit ohne Zone. */
  readonly runsToday = computed(() => {
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Berlin' }).format(new Date());
    return this.runs().filter(run => run.startedAt.startsWith(today)).length;
  });
  readonly expandedRuns = signal<ReadonlySet<number>>(new Set());

  /** Ein Fehlerfeld je Abschnitt, damit ein Abruf den anderen nicht überschreibt. */
  readonly settingsError = signal<string | null>(null);
  readonly policiesError = signal<string | null>(null);
  readonly notesError = signal<string | null>(null);
  readonly proposalsError = signal<string | null>(null);
  readonly runsError = signal<string | null>(null);

  readonly settingsSaving = signal(false);
  /** Bei Speicherfehlern erhöht, damit Select/Checkbox neu aufgebaut werden (ngModel-Rücksetzung). */
  readonly policiesVersion = signal(0);
  readonly settingsVersion = signal(0);
  /** Zeilen mit Versionsschlüssel: ein neuer Schlüssel baut die Zeile (und ihr ngModel) neu auf. */
  readonly policyRows = computed(() =>
    this.policies().map(p => ({ ...p, key: `${p.actionClass}:${this.policiesVersion()}` })));
  readonly noteSaving = signal(false);
  readonly decidingId = signal<number | null>(null);
  readonly running = signal(false);
  readonly runMessage = signal<string | null>(null);
  readonly runMessageType = signal<'success' | 'error' | null>(null);

  newNote: NoteForm = emptyNoteForm();
  /** Id der gerade bearbeiteten Notiz, sonst null. */
  editingNoteId: number | null = null;
  editNote: NoteForm = emptyNoteForm();

  ngOnInit(): void {
    this.api.getSummary().subscribe({
      next: summary => this.summary.set(summary),
      error: error => this.settingsError.set(this.messageFrom(error))
    });
    this.api.getPolicies().subscribe({
      next: policies => this.policies.set(policies),
      error: error => this.policiesError.set(this.messageFrom(error))
    });
    this.api.getNotes().subscribe({
      next: notes => this.notes.set(notes),
      error: error => this.notesError.set(this.messageFrom(error))
    });
    this.api.getProposals(PROPOSAL_LIMIT).subscribe({
      next: proposals => this.proposals.set(proposals),
      error: error => this.proposalsError.set(this.messageFrom(error))
    });
    this.api.getRuns().subscribe({
      next: response => {
        this.runs.set(response.runs);
        this.inputTokensToday.set(response.inputTokensToday);
        this.outputTokensToday.set(response.outputTokensToday);
        this.dailyTokenBudget.set(response.dailyTokenBudget);
        this.budgetExhausted.set(response.budgetExhausted);
      },
      error: error => this.runsError.set(this.messageFrom(error))
    });
  }

  // ---- Betrieb -------------------------------------------------------------

  setActive(active: boolean): void {
    this.settingsSaving.set(true);
    this.settingsError.set(null);
    this.api.updateSettings(active).subscribe({
      next: settings => {
        this.settingsSaving.set(false);
        this.summary.update(current => current ? { ...current, active: settings.active } : current);
      },
      error: error => {
        this.settingsSaving.set(false);
        const message = this.messageFrom(error);
        this.api.getSummary().subscribe({
          next: summary => {
            this.summary.set(summary);
            this.settingsVersion.update(v => v + 1);
            this.settingsError.set(message);
          },
          error: () => this.settingsError.set(message)
        });
      }
    });
  }

  // ---- Freigaben -----------------------------------------------------------

  changePolicy(actionClass: ActionClass, policy: ActionPolicy): void {
    this.policiesError.set(null);
    this.api.updatePolicy(actionClass, policy).subscribe({
      next: policies => this.policies.set(policies),
      // ngModel behält die verworfene Auswahl, solange sich der gebundene Wert nicht ändert:
      // Serverstand nachladen und die Zeilen über die Version im track-Ausdruck neu aufbauen.
      error: error => this.reloadPolicies(this.messageFrom(error))
    });
  }

  private reloadPolicies(message: string): void {
    this.api.getPolicies().subscribe({
      next: policies => {
        this.policies.set(policies);
        this.policiesVersion.update(v => v + 1);
        this.policiesError.set(message);
      },
      error: () => this.policiesError.set(message)
    });
  }

  /** NOTIFY ist bewusst nicht änderbar (Nachrichten laufen immer direkt). */
  isPolicyLocked(actionClass: ActionClass): boolean {
    return actionClass === 'NOTIFY';
  }

  // ---- Notizen -------------------------------------------------------------

  createNote(): void {
    if (!this.newNote.text.trim()) {
      this.notesError.set('Der Notiztext darf nicht leer sein.');
      return;
    }
    this.noteSaving.set(true);
    this.notesError.set(null);
    this.api.createNote(this.toNoteRequest(this.newNote)).subscribe({
      next: note => {
        this.noteSaving.set(false);
        this.notes.update(list => [note, ...list]);
        this.newNote = emptyNoteForm();
      },
      error: error => {
        this.noteSaving.set(false);
        this.notesError.set(this.messageFrom(error));
      }
    });
  }

  startEditNote(note: AgentNote): void {
    this.notesError.set(null);
    this.editingNoteId = note.id;
    this.editNote = {
      kind: note.kind,
      text: note.text,
      expiresAt: note.expiresAt ? note.expiresAt.slice(0, 16) : ''
    };
  }

  cancelEditNote(): void {
    this.editingNoteId = null;
  }

  saveEditNote(): void {
    const id = this.editingNoteId;
    if (id === null) {
      return;
    }
    if (!this.editNote.text.trim()) {
      this.notesError.set('Der Notiztext darf nicht leer sein.');
      return;
    }
    this.noteSaving.set(true);
    this.notesError.set(null);
    this.api.updateNote(id, this.toNoteRequest(this.editNote)).subscribe({
      next: updated => {
        this.noteSaving.set(false);
        this.notes.update(list => list.map(note => note.id === id ? updated : note));
        this.editingNoteId = null;
      },
      error: error => {
        this.noteSaving.set(false);
        this.notesError.set(this.messageFrom(error));
      }
    });
  }

  deleteNote(note: AgentNote): void {
    if (!confirm('Notiz endgültig löschen?')) {
      return;
    }
    this.notesError.set(null);
    this.api.deleteNote(note.id).subscribe({
      next: () => {
        this.notes.update(list => list.filter(candidate => candidate.id !== note.id));
        if (this.editingNoteId === note.id) {
          this.editingNoteId = null;
        }
      },
      error: error => this.notesError.set(this.messageFrom(error))
    });
  }

  private toNoteRequest(form: NoteForm): AgentNoteRequest {
    return {
      kind: form.kind,
      text: form.text.trim(),
      expiresAt: form.expiresAt ? `${form.expiresAt.slice(0, 16)}:00` : null
    };
  }

  // ---- Vorschläge ----------------------------------------------------------

  decide(proposal: AgentProposal, decision: ProposalDecision): void {
    if (this.decidingId() !== null) {
      return;
    }
    this.decidingId.set(proposal.id);
    this.proposalsError.set(null);
    this.api.decide(proposal.id, decision).subscribe({
      next: updated => {
        this.decidingId.set(null);
        this.proposals.update(list => list.map(p => p.id === updated.id ? updated : p));
      },
      error: error => {
        this.decidingId.set(null);
        this.proposalsError.set(this.messageFrom(error));
      }
    });
  }

  // ---- Läufe ---------------------------------------------------------------

  runNow(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.runMessage.set(null);
    this.runMessageType.set(null);
    this.api.runNow().subscribe({
      next: run => {
        this.running.set(false);
        this.runs.update(list => [run, ...list]);
        this.inputTokensToday.update(sum => sum + run.inputTokens);
        this.outputTokensToday.update(sum => sum + run.outputTokens);
        this.runMessageType.set(run.error ? 'error' : 'success');
        this.runMessage.set(run.error ?? run.summary ?? 'Lauf beendet.');
      },
      error: (error: HttpErrorResponse) => {
        this.running.set(false);
        this.runMessageType.set('error');
        this.runMessage.set(error.status === 429 ? RATE_LIMIT_MESSAGE : this.messageFrom(error));
      }
    });
  }

  durationSeconds(run: AgentRun): string {
    return (run.durationMs / 1000).toFixed(1).replace('.', ',');
  }

  private fullResult(run: AgentRun): string {
    return run.error ?? run.summary ?? '–';
  }

  isLongResult(run: AgentRun): boolean {
    return this.fullResult(run).length > RESULT_PREVIEW_LENGTH;
  }

  resultText(run: AgentRun, index: number): string {
    const text = this.fullResult(run);
    return this.expandedRuns().has(index) || text.length <= RESULT_PREVIEW_LENGTH
      ? text
      : `${text.slice(0, RESULT_PREVIEW_LENGTH).trimEnd()} …`;
  }

  toggleRun(index: number): void {
    this.expandedRuns.update(current => {
      const next = new Set(current);
      if (!next.delete(index)) {
        next.add(index);
      }
      return next;
    });
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
