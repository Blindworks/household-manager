import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { FlowService } from '../../services/flow.service';
import { FlowSummary } from '../../models/flow.model';

/** Übersicht aller Automatisierungs-Flows. */
@Component({
  selector: 'app-flow-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './flow-list.component.html',
  styleUrl: './flow-list.component.scss'
})
export class FlowListComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly router = inject(Router);

  readonly flows = signal<FlowSummary[]>([]);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.reload();
  }

  private reload(): void {
    this.flowService.getFlows().subscribe({
      next: flows => {
        this.flows.set(flows);
        this.error.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  createFlow(): void {
    this.flowService.createFlow('Neuer Flow', '').subscribe({
      next: flow => this.router.navigate(['/flows', flow.id]),
      error: err => this.error.set(err.message)
    });
  }

  onImportFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // erlaubt erneuten Upload derselben Datei
    if (!file) { return; }
    file.text().then(text => this.importFromText(text));
  }

  private importFromText(text: string): void {
    let payload: unknown;
    try {
      payload = JSON.parse(text);
    } catch {
      this.error.set('Die Datei enthält kein gültiges JSON.');
      return;
    }
    this.flowService.importFlow(payload).subscribe({
      next: flow => this.router.navigate(['/flows', flow.id]),
      error: err => this.error.set(err.error?.message ?? 'Import fehlgeschlagen.')
    });
  }

  open(flow: FlowSummary): void {
    this.router.navigate(['/flows', flow.id]);
  }

  toggleEnabled(flow: FlowSummary): void {
    this.flowService.setEnabled(flow.id, !flow.enabled).subscribe({
      next: () => this.reload(),
      error: err => this.error.set(err.message)
    });
  }

  deleteFlow(flow: FlowSummary): void {
    if (!confirm(`Flow "${flow.name}" wirklich löschen?`)) { return; }
    this.flowService.deleteFlow(flow.id).subscribe({
      next: () => this.reload(),
      error: err => this.error.set(err.message)
    });
  }
}
