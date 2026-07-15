import { Component, EventEmitter, OnInit, Output, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { EntityStateService } from '../../../services/entity-state.service';
import { EntityState } from '../../../models/entity-state.model';

/** Durchsuchbares Dropdown für Entity-Referenzen (Feldtyp ENTITY_REF). Wert = entityId. */
@Component({
  selector: 'app-entity-picker',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './entity-picker.component.html'
})
export class EntityPickerComponent implements OnInit {
  private readonly entityService = inject(EntityStateService);

  readonly value = input<string>();
  @Output() valueChange = new EventEmitter<string>();

  readonly options = signal<EntityState[]>([]);

  readonly displayLabel = computed(() => {
    const v = this.value();
    if (!v) { return ''; }
    const found = this.options().find(o => o.entityId === v);
    return found ? `${found.displayName} (${found.state})` : `nicht gefunden: ${v}`;
  });

  ngOnInit(): void {
    this.entityService.getEntities().subscribe(list => this.options.set(list));
  }

  select(entityId: string): void {
    this.valueChange.emit(entityId);
  }
}
