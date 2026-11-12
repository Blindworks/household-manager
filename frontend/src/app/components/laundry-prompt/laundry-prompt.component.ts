import { Component, DestroyRef, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { EMPTY, Observable, catchError, interval, startWith, switchMap } from 'rxjs';
import { LaundryService } from '../../services/laundry.service';
import { LaundryState } from '../../models/laundry.model';

/**
 * Frage-Dialog der Waeschetrocknung am Wandtablet: zeigt "Waesche aufgehaengt?" bzw.
 * "Ist die Waesche trocken?" als Overlay, solange der Server eine Frage stellt.
 * Holt den Zustand selbst im 30-s-Takt; ein Fehler laesst den Dialog wie er ist.
 *
 * Eigene Styles, Farben nur ueber lumina-Tokens (steht im .lumina-Wurzelelement).
 */
@Component({
  selector: 'app-laundry-prompt',
  standalone: true,
  templateUrl: './laundry-prompt.component.html',
  styleUrls: ['./laundry-prompt.component.scss']
})
export class LaundryPromptComponent implements OnInit {
  static readonly REFRESH_MS = 30_000;

  /** Neuer Zustand nach einer Antwort, damit die Hub-Karte nicht 30 s warten muss. */
  @Output() answered = new EventEmitter<LaundryState>();

  private readonly service = inject(LaundryService);
  private readonly destroyRef = inject(DestroyRef);

  state: LaundryState | null = null;
  busy = false;

  get prompt(): 'HUNG' | 'DRY' | null {
    const prompt = this.state?.prompt;
    return prompt === 'HUNG' || prompt === 'DRY' ? prompt : null;
  }

  ngOnInit(): void {
    interval(LaundryPromptComponent.REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.service.getState().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(state => (this.state = state));
  }

  answer(yes: boolean): void {
    const prompt = this.prompt;
    if (this.busy || !prompt) {
      return;
    }
    this.busy = true;
    const request: Observable<LaundryState> =
      prompt === 'HUNG' ? this.service.answerHung(yes) : this.service.answerDry(yes);
    request.subscribe({
      next: state => this.adopt(state),
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.reload();
        } else {
          this.busy = false;
        }
      }
    });
  }

  /** Die Frage ist anderweitig erledigt: Zustand frisch laden. */
  private reload(): void {
    this.service.getState().subscribe({
      next: state => this.adopt(state),
      error: () => (this.busy = false)
    });
  }

  private adopt(state: LaundryState): void {
    this.busy = false;
    this.state = state;
    this.answered.emit(state);
  }
}
