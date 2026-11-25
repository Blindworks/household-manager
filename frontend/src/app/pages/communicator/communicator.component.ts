import { Component, NgZone, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { CommunicatorAnswer, CommunicatorCard } from '../../models/communicator.model';
import { AuthService } from '../../services/auth.service';
import { CommunicatorService } from '../../services/communicator.service';
import { CommunicatorCardComponent } from './communicator-card/communicator-card.component';
import { QUICK_QUESTIONS, formatDayLine, greetingFor } from './greeting';
import { SignalTones } from './signal-tones';
import { ThinkingHouseComponent } from './thinking-house/thinking-house.component';
import { VoiceRecorder } from './voice-recorder';

type Phase = 'idle' | 'starting' | 'recording' | 'thinking';

/** Eine Frage ist kurz; nach dieser Zeit endet die Aufnahme von selbst. */
export const MAX_RECORDING_MS = 30_000;

interface ShownCard {
  /** Eindeutig je Antwort, damit eine neue Antwort neue Karten-Komponenten erzeugt (kein alter Hinweistext). */
  key: string;
  card: CommunicatorCard;
}

/**
 * Sprach-App „Communicator“: ein grosser Knopf, eine gesprochene Frage, grosse Karten als Antwort.
 * Antippen startet die Aufnahme, erneutes Antippen beendet sie und schickt sie ab.
 * Die Seite liegt als Vollbild ueber dem Rest der App (kein Header), der Rueckweg ist das Haus-Symbol.
 */
@Component({
  selector: 'app-communicator',
  standalone: true,
  imports: [FormsModule, RouterLink, CommunicatorCardComponent, ThinkingHouseComponent],
  templateUrl: './communicator.component.html',
  styleUrl: './communicator.component.scss'
})
export class CommunicatorComponent implements OnInit, OnDestroy {
  private readonly service = inject(CommunicatorService);
  private readonly recorder = new VoiceRecorder();
  private readonly tones = new SignalTones();
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  readonly phase = signal<Phase>('idle');
  readonly cards = signal<ShownCard[]>([]);
  readonly transcript = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly keyboardOpen = signal(false);
  /** Null, solange der Status nicht geladen ist — dann wird nichts behauptet. */
  readonly speechAvailable = signal<boolean | null>(null);
  text = '';

  readonly quickQuestions = QUICK_QUESTIONS;
  private readonly now = signal(new Date());
  readonly greeting = computed(() => greetingFor(this.now(), this.auth.currentUser()?.displayName));
  readonly dayLine = computed(() => formatDayLine(this.now()));
  readonly clock = computed(() => this.now().toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' }));

  private answerCount = 0;
  private recordingSince = 0;
  private autoStop: ReturnType<typeof setTimeout> | null = null;
  private clockTimer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    // Ausserhalb der Zone, sonst wird die Seite nie „stabil“ (Tests, Hydration); Signale aktualisieren trotzdem.
    this.zone.runOutsideAngular(() => {
      this.clockTimer = setInterval(() => this.now.set(new Date()), 15_000);
    });
    const recordable = VoiceRecorder.isSupported();
    this.service.getStatus().subscribe({
      next: status => {
        const available = recordable && status.speechConfigured;
        this.speechAvailable.set(available);
        if (!available) {
          this.keyboardOpen.set(true);
        }
      },
      // Status nicht lesbar: nichts sperren, die Frage selbst meldet einen Fehler.
      error: () => this.speechAvailable.set(recordable)
    });
  }

  ngOnDestroy(): void {
    if (this.clockTimer) {
      clearInterval(this.clockTimer);
    }
    this.clearAutoStop();
    this.recorder.cancel();
    this.tones.close();
  }

  onMicTap(): void {
    if (this.phase() === 'thinking' || this.phase() === 'starting') {
      return;
    }
    if (this.phase() === 'recording') {
      void this.stopAndSend();
    } else {
      void this.startRecording();
    }
  }

  private async startRecording(): Promise<void> {
    this.error.set(null);
    this.phase.set('starting');
    this.tones.unlock();
    try {
      if (!(await this.recorder.start(() => this.tones.start()))) {
        return;
      }
    } catch {
      this.phase.set('idle');
      this.error.set('Kein Zugriff auf das Mikrofon. Bitte in den Einstellungen erlauben.');
      return;
    }
    this.phase.set('recording');
    this.recordingSince = Date.now();
    this.autoStop = setTimeout(() => void this.stopAndSend(), MAX_RECORDING_MS);
  }

  private async stopAndSend(): Promise<void> {
    this.clearAutoStop();
    this.phase.set('starting');
    const durationMs = Date.now() - this.recordingSince;
    const recording = await this.recorder.stop();
    void this.tones.stop();
    if (!recording) {
      this.phase.set('idle');
      return;
    }
    this.send(this.service.askAudio(recording, durationMs));
  }

  submitText(): void {
    const question = this.text.trim();
    if (!question || this.phase() !== 'idle') {
      return;
    }
    this.text = '';
    this.askQuestion(question);
  }

  /** Folgefrage aus einem Vorschlag einer Karte. */
  askQuestion(question: string): void {
    if (this.phase() !== 'idle') {
      return;
    }
    this.transcript.set(question);
    this.send(this.service.askText(question));
  }

  toggleKeyboard(): void {
    this.keyboardOpen.update(open => !open);
  }

  private send(request: Observable<CommunicatorAnswer>): void {
    this.phase.set('thinking');
    this.error.set(null);
    request.subscribe({
      next: answer => {
        this.answerCount++;
        this.transcript.set(answer.transcript ?? null);
        this.cards.set((answer.cards ?? []).map((card, index) => ({ key: `${this.answerCount}-${index}`, card })));
        this.phase.set('idle');
      },
      error: () => {
        this.error.set('Keine Antwort vom Haus. Bitte noch einmal versuchen.');
        this.phase.set('idle');
      }
    });
  }

  private clearAutoStop(): void {
    if (this.autoStop) {
      clearTimeout(this.autoStop);
      this.autoStop = null;
    }
  }
}
