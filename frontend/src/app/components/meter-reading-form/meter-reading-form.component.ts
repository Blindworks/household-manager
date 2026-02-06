import { Component, EventEmitter, Input, Output, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { MeterReadingService } from '../../services/meter-reading.service';
import { MeterType, MeterReadingRequest } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Formular-Komponente für das Erstellen neuer Zählerablesungen
 */
@Component({
  selector: 'app-meter-reading-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './meter-reading-form.component.html',
  styleUrl: './meter-reading-form.component.scss'
})
export class MeterReadingFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly meterReadingService = inject(MeterReadingService);

  /** Vorausgewählter Zählertyp (optional) */
  @Input() preselectedType?: MeterType;

  /** Event, das bei erfolgreicher Erstellung gefeuert wird */
  @Output() readingCreated = new EventEmitter<void>();

  /** Formular-Gruppe */
  readingForm!: FormGroup;

  /** Verfügbare Zählertypen */
  meterTypes = MeterTypeUtils.getAllTypes();

  /** MeterTypeUtils für Template-Zugriff */
  meterTypeUtils = MeterTypeUtils;

  /** Loading-Status */
  isSubmitting = false;

  /** Success-Message */
  successMessage: string | null = null;

  /** Error-Message */
  errorMessage: string | null = null;

  /** Heutiges Datum für max-Validierung */
  readonly today = new Date().toISOString().split('T')[0];

  ngOnInit(): void {
    this.initializeForm();
  }

  /**
   * Initialisiert das Formular mit Validierungen
   */
  private initializeForm(): void {
    this.readingForm = this.fb.group({
      meterType: [this.preselectedType || '', Validators.required],
      readingValue: [
        '',
        [Validators.required, Validators.min(0), Validators.pattern(/^\d+(\.\d{1,2})?$/)]
      ],
      readingDate: [this.today, Validators.required],
      notes: ['', Validators.maxLength(500)]
    });
  }

  /**
   * Gibt das heutige Datum im ISO-Format zurück
   */
  getTodayDate(): string {
    return new Date().toISOString().split('T')[0];
  }

  /**
   * Prüft, ob ein Feld ungültig ist und berührt wurde
   */
  isFieldInvalid(fieldName: string): boolean {
    const field = this.readingForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }

  /**
   * Gibt die Fehlermeldung für ein Feld zurück
   */
  getFieldError(fieldName: string): string {
    const field = this.readingForm.get(fieldName);
    if (!field || !field.errors) {
      return '';
    }

    if (field.errors['required']) {
      return 'Dieses Feld ist erforderlich';
    }
    if (field.errors['min']) {
      return 'Der Wert muss mindestens 0 sein';
    }
    if (field.errors['pattern']) {
      return 'Bitte geben Sie eine gültige Zahl ein (max. 2 Dezimalstellen)';
    }
    if (field.errors['maxlength']) {
      return `Maximal ${field.errors['maxlength'].requiredLength} Zeichen erlaubt`;
    }

    return 'Ungültiger Wert';
  }

  /**
   * Behandelt das Absenden des Formulars
   */
  onSubmit(): void {
    if (this.readingForm.invalid || this.isSubmitting) {
      // Markiere alle Felder als berührt, um Fehler anzuzeigen
      Object.keys(this.readingForm.controls).forEach(key => {
        this.readingForm.get(key)?.markAsTouched();
      });
      return;
    }

    this.isSubmitting = true;
    this.successMessage = null;
    this.errorMessage = null;

    const formValue = this.readingForm.value;
    const request: MeterReadingRequest = {
      meterType: formValue.meterType,
      readingValue: parseFloat(formValue.readingValue),
      readingDate: formValue.readingDate,
      notes: formValue.notes || undefined
    };

    this.meterReadingService.createReading(request).subscribe({
      next: () => {
        this.handleSuccess();
      },
      error: (error: Error) => {
        this.handleError(error);
      }
    });
  }

  /**
   * Behandelt erfolgreiche Formular-Absendung
   */
  private handleSuccess(): void {
    this.successMessage = 'Zählerstand erfolgreich erfasst!';
    this.isSubmitting = false;
    this.readingForm.reset({
      meterType: this.preselectedType || '',
      readingDate: this.today
    });
    this.readingCreated.emit();

    // Success-Message nach 3 Sekunden ausblenden
    setTimeout(() => {
      this.successMessage = null;
    }, 3000);
  }

  /**
   * Behandelt Fehler bei Formular-Absendung
   */
  private handleError(error: Error): void {
    this.errorMessage = error.message;
    this.isSubmitting = false;

    // Error-Message nach 5 Sekunden ausblenden
    setTimeout(() => {
      this.errorMessage = null;
    }, 5000);
  }

  /**
   * Setzt das Formular zurück
   */
  resetForm(): void {
    this.readingForm.reset({
      meterType: this.preselectedType || '',
      readingDate: this.today
    });
    this.successMessage = null;
    this.errorMessage = null;
  }
}
