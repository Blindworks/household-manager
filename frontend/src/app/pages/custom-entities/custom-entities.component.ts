import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';
import { EntityStateService } from '../../services/entity-state.service';
import { IconPickerComponent } from '../../components/icon-picker/icon-picker.component';
import {
  EntityState,
  CreateManualEntityRequest,
  ManualEntityType,
  MANUAL_SOURCE
} from '../../models/entity-state.model';

const REFRESH_INTERVAL_MS = 10000;

interface HelperTypeOption {
  value: ManualEntityType;
  label: string;
}

/**
 * Seite zum Anlegen und Steuern eigener Entitäten (Helfer): schaltbare Modi
 * (Boolean), Zahlen, Freitext und Auswahlwerte – z. B. "Nachtmodus",
 * "Zieltemperatur" oder "Urlaubsstatus". Manuelle Entitäten werden vom Benutzer
 * erstellt und je nach Typ direkt hier bedient, umbenannt oder gelöscht.
 */
@Component({
  selector: 'app-custom-entities',
  standalone: true,
  imports: [CommonModule, FormsModule, IconPickerComponent],
  templateUrl: './custom-entities.component.html',
  styleUrl: './custom-entities.component.scss'
})
export class CustomEntitiesComponent implements OnInit {
  private readonly entityStateService = inject(EntityStateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly typeOptions: HelperTypeOption[] = [
    { value: 'INPUT_BOOLEAN', label: 'Schalter (an/aus)' },
    { value: 'INPUT_NUMBER', label: 'Zahl' },
    { value: 'INPUT_TEXT', label: 'Text' },
    { value: 'INPUT_SELECT', label: 'Auswahl' }
  ];

  readonly entities = signal<EntityState[]>([]);
  readonly error = signal<string | null>(null);
  readonly saving = signal<boolean>(false);

  readonly newName = signal<string>('');
  readonly newType = signal<ManualEntityType>('INPUT_BOOLEAN');
  readonly newIcon = signal<string>('');
  readonly newOptions = signal<string>('');
  readonly newMin = signal<string>('');
  readonly newMax = signal<string>('');
  readonly newStep = signal<string>('');
  readonly newUnit = signal<string>('');

  readonly editingId = signal<string | null>(null);
  readonly editName = signal<string>('');
  readonly editIcon = signal<string>('');

  ngOnInit(): void {
    interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.entityStateService.getEntities(undefined, MANUAL_SOURCE)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: entities => {
        this.entities.set(entities);
        this.error.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  create(): void {
    const name = this.newName().trim();
    if (!name || this.saving()) {
      return;
    }
    const request = this.buildCreateRequest(name);
    this.saving.set(true);
    this.entityStateService.createManualEntity(request).subscribe({
      next: entity => {
        this.entities.update(list => [...list, entity].sort((a, b) => a.entityId.localeCompare(b.entityId)));
        this.resetForm();
        this.error.set(null);
        this.saving.set(false);
      },
      error: err => {
        this.error.set(err.message);
        this.saving.set(false);
      }
    });
  }

  private buildCreateRequest(name: string): CreateManualEntityRequest {
    const type = this.newType();
    const request: CreateManualEntityRequest = {
      name,
      type,
      icon: this.newIcon().trim() || undefined
    };
    if (type === 'INPUT_NUMBER') {
      request.min = this.parseNumber(this.newMin());
      request.max = this.parseNumber(this.newMax());
      request.step = this.parseNumber(this.newStep());
      request.unit = this.newUnit().trim() || undefined;
    }
    if (type === 'INPUT_SELECT') {
      request.options = this.parseOptions(this.newOptions());
    }
    return request;
  }

  private resetForm(): void {
    this.newName.set('');
    this.newIcon.set('');
    this.newOptions.set('');
    this.newMin.set('');
    this.newMax.set('');
    this.newStep.set('');
    this.newUnit.set('');
  }

  toggle(entity: EntityState): void {
    this.entityStateService.toggleManualEntity(entity.entityId).subscribe({
      next: updated => this.replace(updated),
      error: err => this.error.set(err.message)
    });
  }

  setValue(entity: EntityState, event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLSelectElement).value;
    this.entityStateService.setManualState(entity.entityId, value).subscribe({
      next: updated => this.replace(updated),
      error: err => this.error.set(err.message)
    });
  }

  startEdit(entity: EntityState): void {
    this.editingId.set(entity.entityId);
    this.editName.set(entity.friendlyName);
    this.editIcon.set(this.iconOf(entity));
  }

  cancelEdit(): void {
    this.editingId.set(null);
  }

  saveEdit(entity: EntityState): void {
    const name = this.editName().trim();
    if (!name) {
      return;
    }
    this.entityStateService.renameManualEntity(entity.entityId, {
      name,
      icon: this.editIcon().trim() || undefined
    }).subscribe({
      next: updated => {
        this.replace(updated);
        this.editingId.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  remove(entity: EntityState): void {
    if (!confirm(`"${entity.friendlyName}" wirklich löschen?`)) {
      return;
    }
    this.entityStateService.deleteManualEntity(entity.entityId).subscribe({
      next: () => this.entities.update(list => list.filter(e => e.entityId !== entity.entityId)),
      error: err => this.error.set(err.message)
    });
  }

  isBoolean(entity: EntityState): boolean { return entity.domain === 'INPUT_BOOLEAN'; }
  isNumber(entity: EntityState): boolean { return entity.domain === 'INPUT_NUMBER'; }
  isText(entity: EntityState): boolean { return entity.domain === 'INPUT_TEXT'; }
  isSelect(entity: EntityState): boolean { return entity.domain === 'INPUT_SELECT'; }

  isOn(entity: EntityState): boolean {
    return entity.state === 'on';
  }

  iconOf(entity: EntityState): string {
    const icon = entity.attributes?.['icon'];
    return typeof icon === 'string' ? icon : '';
  }

  optionsOf(entity: EntityState): string[] {
    const options = entity.attributes?.['options'];
    return Array.isArray(options) ? options.map(o => String(o)) : [];
  }

  unitOf(entity: EntityState): string {
    const unit = entity.attributes?.['unit'];
    return typeof unit === 'string' ? unit : '';
  }

  numberAttr(entity: EntityState, key: 'min' | 'max' | 'step'): number | null {
    const value = entity.attributes?.[key];
    return typeof value === 'number' ? value : null;
  }

  typeLabel(entity: EntityState): string {
    return this.typeOptions.find(t => t.value === entity.domain)?.label ?? entity.domain;
  }

  private parseNumber(raw: string): number | undefined {
    const trimmed = raw.trim();
    if (!trimmed) {
      return undefined;
    }
    const value = Number(trimmed);
    return Number.isNaN(value) ? undefined : value;
  }

  private parseOptions(raw: string): string[] {
    return raw.split('\n').map(o => o.trim()).filter(o => o.length > 0);
  }

  private replace(updated: EntityState): void {
    this.entities.update(list => list.map(e => e.entityId === updated.entityId ? updated : e));
  }
}
