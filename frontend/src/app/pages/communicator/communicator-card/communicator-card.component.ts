import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import {
  CommunicatorAction,
  CommunicatorBlock,
  CommunicatorCard,
  CommunicatorPoint
} from '../../../models/communicator.model';
import { CommunicatorService } from '../../../services/communicator.service';

const VALUE_PLACEHOLDER = '$value';

/**
 * Zeigt eine Karte aus allgemeinen Bausteinen und führt deren Aktionen aus, aber nur auf Tipp des Nutzers.
 * Kennt bewusst kein einzelnes Feature (Heizung, Licht, …): alles Nötige steht in der Karte.
 */
@Component({
  selector: 'app-communicator-card',
  standalone: true,
  templateUrl: './communicator-card.component.html',
  styleUrl: './communicator-card.component.scss'
})
export class CommunicatorCardComponent {
  private readonly service = inject(CommunicatorService);

  readonly busy = signal(false);
  /** Ausgeführte Aktionen bleiben gesperrt: ein zweiter Tipp auf einen Umschalter kehrte sie um. */
  readonly done = signal<ReadonlySet<CommunicatorAction>>(new Set());
  readonly notice = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly sliderValues = signal<Record<number, number>>({});

  /** Folgefrage; die Seite stellt sie wie eine gesprochene Frage. */
  @Output() readonly ask = new EventEmitter<string>();

  @Input({ required: true }) card!: CommunicatorCard;

  run(action: CommunicatorAction): void {
    this.execute(action, action.body, action.label, () =>
      this.done.update(done => new Set(done).add(action))
    );
  }

  applySlider(block: CommunicatorBlock, index: number): void {
    if (block.action) {
      const value = this.sliderValue(block, index);
      this.execute(block.action, this.fill(block.action.body, value), `${block.action.label}: ${this.number(value)} ${block.unit ?? ''}`.trim());
    }
  }

  sliderValue(block: CommunicatorBlock, index: number): number {
    return this.sliderValues()[index] ?? block.number ?? 0;
  }

  changeSlider(block: CommunicatorBlock, index: number, direction: 1 | -1): void {
    const step = block.step ?? 1;
    const min = block.min ?? 0;
    const raw = this.sliderValue(block, index) + direction * step;
    // Aufs Raster ab min runden, sonst sammeln sich Rundungsfehler (0,1 + 0,2).
    const snapped = Number((min + Math.round((raw - min) / step) * step).toFixed(3));
    const value = Math.min(block.max ?? snapped, Math.max(min, snapped));
    this.sliderValues.update(values => ({ ...values, [index]: value }));
  }

  number(value: number): string {
    return value.toLocaleString('de-DE', { maximumFractionDigits: 2 });
  }

  /** Linienzug im 100×40-Feld. */
  chartPath(points: CommunicatorPoint[]): string {
    const { min, max } = this.range(points);
    const span = max - min || 1;
    return points
      .map((point, i) => `${(i / (points.length - 1)) * 100},${38 - ((point.value - min) / span) * 36}`)
      .join(' ');
  }

  range(points: CommunicatorPoint[]): { min: number; max: number } {
    const values = points.map(point => point.value);
    return { min: Math.min(...values), max: Math.max(...values) };
  }

  private execute(action: CommunicatorAction, body: unknown, label: string, onSuccess?: () => void): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    this.error.set(null);
    this.service.execute(action, body).subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set(`Erledigt: ${label}`);
        onSuccess?.();
      },
      error: (error: { error?: { message?: string } }) => {
        this.busy.set(false);
        this.error.set(error.error?.message ?? 'Das hat nicht geklappt.');
      }
    });
  }

  private fill(body: unknown, value: number): unknown {
    if (body === VALUE_PLACEHOLDER) {
      return value;
    }
    if (Array.isArray(body)) {
      return body.map(entry => this.fill(entry, value));
    }
    return body && typeof body === 'object'
      ? Object.fromEntries(Object.entries(body).map(([key, entry]) => [key, this.fill(entry, value)]))
      : body;
  }
}
