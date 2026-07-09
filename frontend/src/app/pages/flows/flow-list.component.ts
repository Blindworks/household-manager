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

  ngOnInit(): void {
    this.reload();
  }

  private reload(): void {
    this.flowService.getFlows().subscribe(flows => this.flows.set(flows));
  }

  createFlow(): void {
    this.flowService.createFlow('Neuer Flow', '').subscribe(flow => this.router.navigate(['/flows', flow.id]));
  }

  open(flow: FlowSummary): void {
    this.router.navigate(['/flows', flow.id]);
  }

  toggleEnabled(flow: FlowSummary): void {
    this.flowService.setEnabled(flow.id, !flow.enabled).subscribe(() => this.reload());
  }

  deleteFlow(flow: FlowSummary): void {
    this.flowService.deleteFlow(flow.id).subscribe(() => this.reload());
  }
}
