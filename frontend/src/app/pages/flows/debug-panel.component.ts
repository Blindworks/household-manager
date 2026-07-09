import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap, forkJoin, of, Subscription } from 'rxjs';
import { FlowService } from '../../services/flow.service';
import { DebugEntry } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';

const POLL_MS = 2000;

/** Debug-Tab: pollt die Debug-Puffer der Debug-Nodes, solange sichtbar und deployed. */
@Component({
  selector: 'app-debug-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './debug-panel.component.html',
  styleUrl: './debug-panel.component.scss'
})
export class DebugPanelComponent {
  private readonly flowService = inject(FlowService);
  private readonly destroyRef = inject(DestroyRef);

  readonly flowId = input.required<number>();
  readonly nodes = input<CanvasNode[]>([]);
  readonly deployed = input(false);
  readonly active = input(false);

  readonly entries = signal<DebugEntry[]>([]);
  private sub?: Subscription;

  constructor() {
    effect(() => {
      const shouldPoll = this.active() && this.deployed();
      this.sub?.unsubscribe();
      if (!shouldPoll) {
        return;
      }
      const debugNodeIds = this.nodes().filter(n => n.type === 'debug').map(n => n.id);
      this.sub = interval(POLL_MS).pipe(
        startWith(0),
        switchMap(() => debugNodeIds.length
          ? forkJoin(debugNodeIds.map(id => this.flowService.getDebug(this.flowId(), id)))
          : of([] as DebugEntry[][])),
        takeUntilDestroyed(this.destroyRef)
      ).subscribe(perNode => {
        const all = perNode.flat().sort((a, b) => a.timestamp.localeCompare(b.timestamp));
        this.entries.set(all);
      });
    });
  }
}
