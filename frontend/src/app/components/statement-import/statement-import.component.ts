import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, ImportSummary } from '../../models/finance.model';

/**
 * Uploads a CAMT (camt.053) statement file for a selected account.
 */
@Component({
  selector: 'app-statement-import',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './statement-import.component.html',
  styleUrl: './statement-import.component.scss'
})
export class StatementImportComponent {
  private readonly financeService = inject(FinanceService);

  @Input() accounts: BankAccount[] = [];
  @Input() selectedAccountId: number | null = null;
  @Output() importCompleted = new EventEmitter<ImportSummary>();

  selectedFile: File | null = null;
  isUploading = false;
  summary: ImportSummary | null = null;
  errorMessage: string | null = null;

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files && input.files.length > 0 ? input.files[0] : null;
    this.summary = null;
    this.errorMessage = null;
  }

  upload(): void {
    if (!this.selectedFile || this.selectedAccountId == null || this.isUploading) {
      return;
    }
    this.isUploading = true;
    this.summary = null;
    this.errorMessage = null;

    this.financeService.importStatement(this.selectedAccountId, this.selectedFile).subscribe({
      next: (result) => {
        this.isUploading = false;
        this.summary = result;
        this.importCompleted.emit(result);
      },
      error: (error: Error) => {
        this.isUploading = false;
        this.errorMessage = error.message;
      }
    });
  }
}
