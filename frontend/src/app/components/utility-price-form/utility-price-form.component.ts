import { Component, EventEmitter, Input, Output, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { UtilityPriceService } from '../../services/utility-price.service';
import { UtilityPriceRequest } from '../../models/utility-price.model';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Formular-Komponente für das Erstellen neuer Versorgerpreise
 */
@Component({
  selector: 'app-utility-price-form',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, IconComponent],
  templateUrl: './utility-price-form.component.html',
  styleUrl: './utility-price-form.component.scss'
})
export class UtilityPriceFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly utilityPriceService = inject(UtilityPriceService);

  /** Vorausgewählter Zählertyp (optional) */
  @Input() preselectedType?: MeterType;

  /** Event, das bei erfolgreicher Erstellung gefeuert wird */
  @Output() priceCreated = new EventEmitter<void>();

  /** Formular-Gruppe */
  priceForm!: FormGroup;

  /** Verfügbare Zählertypen */
  meterTypes: MeterType[] = [MeterType.ELECTRICITY, MeterType.GAS];

  /** MeterTypeUtils für Template-Zugriff */
  meterTypeUtils = MeterTypeUtils;

  /** Loading-Status */
  isSubmitting = false;

  /** Success-Message */
  successMessage: string | null = null;

  /** Error-Message */
  errorMessage: string | null = null;

  /** Heutiges Datum für min-Validierung */
  readonly today = new Date().toISOString().split('T')[0];

  ngOnInit(): void {
    this.initializeForm();
  }

  /**
   * Initialisiert das Formular mit Validierungen
   */
  private initializeForm(): void {
    this.priceForm = this.fb.group({
      meterType: [this.preselectedType || '', Validators.required],
      price: [
        '',
        [Validators.required, Validators.min(0.0001), Validators.pattern(/^\d+(\.\d{1,4})?$/)]
      ],
      validFrom: [this.today, Validators.required],
      validTo: ['']
    }, { validators: this.dateRangeValidator });
  }

  /**
   * Custom Validator: Prüft, ob validFrom vor validTo liegt
   */
  private dateRangeValidator(group: FormGroup): { [key: string]: boolean } | null {
    const validFrom = group.get('validFrom')?.value;
    const validTo = group.get('validTo')?.value;

    if (validFrom && validTo && validFrom >= validTo) {
      return { 'dateRangeInvalid': true };
    }

    return null;
  }

  /**
   * Prüft, ob ein Feld ungültig ist und berührt wurde
   */
  isFieldInvalid(fieldName: string): boolean {
    const field = this.priceForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }

  /**
   * Prüft, ob der Datumsbereich ungültig ist
   */
  isDateRangeInvalid(): boolean {
    return !!(
      this.priceForm.errors?.['dateRangeInvalid'] &&
      this.priceForm.get('validFrom')?.touched &&
      this.priceForm.get('validTo')?.touched
    );
  }

  /**
   * Gibt die Fehlermeldung für ein Feld zurück
   */
  getFieldError(fieldName: string): string {
    const field = this.priceForm.get(fieldName);
    if (!field || !field.errors) {
      return '';
    }

    if (field.errors['required']) {
      return 'Dieses Feld ist erforderlich';
    }
    if (field.errors['min']) {
      return 'Der Preis muss größer als 0 sein';
    }
    if (field.errors['pattern']) {
      return 'Bitte geben Sie einen gültigen Preis ein (max. 4 Dezimalstellen)';
    }

    return 'Ungültiger Wert';
  }

  /**
   * Behandelt das Absenden des Formulars
   */
  onSubmit(): void {
    if (this.priceForm.invalid || this.isSubmitting) {
      // Markiere alle Felder als berührt, um Fehler anzuzeigen
      Object.keys(this.priceForm.controls).forEach(key => {
        this.priceForm.get(key)?.markAsTouched();
      });
      return;
    }

    this.isSubmitting = true;
    this.successMessage = null;
    this.errorMessage = null;

    const formValue = this.priceForm.value;
    const request: UtilityPriceRequest = {
      meterType: formValue.meterType,
      price: parseFloat(formValue.price),
      validFrom: formValue.validFrom,
      validTo: formValue.validTo || undefined
    };

    this.utilityPriceService.createPrice(request).subscribe({
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
    this.successMessage = 'Preis erfolgreich erfasst!';
    this.isSubmitting = false;
    this.priceForm.reset({
      meterType: this.preselectedType || '',
      validFrom: this.today
    });
    this.priceCreated.emit();

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
    this.priceForm.reset({
      meterType: this.preselectedType || '',
      validFrom: this.today
    });
    this.successMessage = null;
    this.errorMessage = null;
  }
}
