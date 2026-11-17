import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Subject, Subscription, catchError, of, switchMap, take, timer } from 'rxjs';
import { GroceryService } from './grocery.service';
import { GroceryRunStatus } from '../models/grocery.model';

/** „Jetzt suchen“ läuft im Hintergrund: Laufstand alle 10 s, höchstens 15 Minuten lang. */
const RUN_POLL_MS = 10_000;
const RUN_MAX_POLLS = 90;

export interface GroceryRunMessage {
  text: string;
  type: 'success' | 'error';
}

/**
 * Startet „Jetzt suchen“ und verfolgt den Lauf bis zum Ende. Einzige Definition für Angebote- und Admin-Seite;
 * je Seite in den `providers` der Komponente, damit das Polling mit der Seite endet.
 */
@Injectable()
export class GroceryRunTracker implements OnDestroy {
  private readonly api = inject(GroceryService);

  readonly running = signal(false);
  readonly message = signal<GroceryRunMessage | null>(null);
  /** Feuert nach dem Ende eines verfolgten Laufs (auch bei Fehler), damit die Seite neu lädt. */
  readonly finished = new Subject<GroceryRunStatus>();

  private poll: Subscription | null = null;
  /** `finishedAt` des Laufstands, zuletzt vom Server gelesen; undefined = nie gelesen. */
  private knownFinishedAt: string | null | undefined = undefined;

  /** Beim Laden: läuft gerade ein Lauf (Nachtlauf oder anderer Tab), wird er wie nach einem Klick verfolgt. */
  init(): void {
    this.api.getRunStatus().subscribe({
      next: status => {
        this.knownFinishedAt = status.finishedAt;
        if (status.running && !this.running()) {
          this.running.set(true);
          this.message.set({ text: 'Suche läuft …', type: 'success' });
          this.pollStatus(status.finishedAt);
        }
      },
      error: () => {
        // Ohne Laufstand bleibt der Knopf bedienbar; ein Klick verfolgt den Lauf trotzdem
      }
    });
  }

  /**
   * Startet den Lauf (202) und fragt danach den Laufstand ab, bis der Server `running: false` mit einem anderen
   * `finishedAt` meldet als vor dem Klick. Verglichen wird der Serverstand als Text, nie die Browseruhr.
   */
  start(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.message.set(null);
    const baseline = this.knownFinishedAt;
    this.api.runNow().subscribe({
      next: () => {
        this.message.set({ text: 'Suche läuft …', type: 'success' });
        this.pollStatus(baseline);
      },
      error: error => {
        this.running.set(false);
        this.message.set({ text: error.message, type: 'error' });
      }
    });
  }

  ngOnDestroy(): void {
    this.poll?.unsubscribe();
    this.finished.complete();
  }

  /** `baseline` undefined = unbekannt (Laufstand nie gelesen): dann zählt erst ein Ende nach gesehenem Lauf. */
  private pollStatus(baseline: string | null | undefined): void {
    this.poll?.unsubscribe();
    let seenRunning = false;
    this.poll = timer(RUN_POLL_MS, RUN_POLL_MS).pipe(
      take(RUN_MAX_POLLS),
      switchMap(() => this.api.getRunStatus().pipe(catchError(() => of(null))))
    ).subscribe({
      next: status => {
        if (status === null) {
          return;
        }
        seenRunning = seenRunning || status.running;
        if (!isRunFinished(status, baseline, seenRunning)) {
          return;
        }
        this.knownFinishedAt = status.finishedAt;
        this.running.set(false);
        this.message.set(runOutcome(status));
        this.poll?.unsubscribe();
        this.finished.next(status);
      },
      complete: () => {
        if (this.running()) {
          this.running.set(false);
          this.message.set({ text: 'Die Suche läuft noch – den Stand später hier prüfen.', type: 'success' });
        }
      }
    });
  }
}

function isRunFinished(status: GroceryRunStatus, baseline: string | null | undefined, seenRunning: boolean): boolean {
  if (status.running || status.finishedAt === null) {
    return false;
  }
  return baseline === undefined ? seenRunning : status.finishedAt !== baseline;
}

function runOutcome(status: GroceryRunStatus): GroceryRunMessage {
  if (status.lastError !== null || status.lastResult === null) {
    return { text: `Suche fehlgeschlagen: ${status.lastError ?? 'unbekannter Fehler'}`, type: 'error' };
  }
  const result = status.lastResult;
  const parts = [`Suche fertig: ${result.offers} Angebote`, `${result.deals} Schnäppchen`];
  if (result.aiFailures > 0) {
    parts.push(`${result.aiFailures} Claude-Bewertungen fehlgeschlagen`);
  }
  const failures = Object.entries(result.sourceFailures);
  if (failures.length > 0) {
    parts.push(`gestört: ${failures.map(([source, reason]) => `${source} (${reason})`).join(', ')}`);
  }
  return { text: parts.join(', ') + '.', type: failures.length > 0 || result.aiFailures > 0 ? 'error' : 'success' };
}
