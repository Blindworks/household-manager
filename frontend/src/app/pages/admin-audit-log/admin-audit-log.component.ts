import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuditLogService } from '../../services/audit-log.service';
import { AuditEntry } from '../../models/auth.model';

@Component({
  selector: 'app-admin-audit-log',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-audit-log.component.html',
  styleUrl: './admin-audit-log.component.scss'
})
export class AdminAuditLogComponent implements OnInit {
  private readonly auditLogService = inject(AuditLogService);

  entries: AuditEntry[] = [];
  actorFilter = '';
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.auditLogService.getEntries(200, this.actorFilter || undefined).subscribe({
      next: entries => (this.entries = entries),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
