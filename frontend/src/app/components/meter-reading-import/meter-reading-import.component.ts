import { Component, EventEmitter, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MeterReadingService } from '../../services/meter-reading.service';
import { IconComponent } from '../../shared/components/icon/icon.component';

/**
 * CSV import component for meter readings.
 */
@Component({
  selector: 'app-meter-reading-import',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './meter-reading-import.component.html',
  styleUrl: './meter-reading-import.component.scss'
})
export class MeterReadingImportComponent {
  private readonly meterReadingService = inject(MeterReadingService);

  @Output() importCompleted = new EventEmitter<void>();

  selectedFile: File | null = null;
  isUploading = false;
  successMessage: string | null = null;
  errorMessage: string | null = null;

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) {
      this.selectedFile = null;
      return;
    }
    this.selectedFile = input.files[0];
    this.successMessage = null;
    this.errorMessage = null;
  }

  upload(): void {
    if (!this.selectedFile || this.isUploading) {
      return;
    }

    this.isUploading = true;
    this.successMessage = null;
    this.errorMessage = null;

    this.meterReadingService.importCsv(this.selectedFile).subscribe({
      next: (result) => {
        this.isUploading = false;
        this.successMessage = `Import abgeschlossen: ${result.createdCount} Einträge erstellt.`;
        this.importCompleted.emit();
      },
      error: (error: Error) => {
        this.isUploading = false;
        this.errorMessage = error.message;
      }
    });
  }
}
